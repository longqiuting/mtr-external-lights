#version 150

// Screen-space lamp scattering - fragment stage.
//
// One pass over the visible frame, two terms per lamp, then ONE composite that replaces the screen.
//
//   SURFACE  the lamp really lights the ground / wall / car body this pixel is looking at. The
//            albedo comes from the scene-colour copy, the face normal from the screen-space
//            derivatives of the reconstructed position:
//
//              spot  = smoothstep(cosOuter, cosInner, dot(beamAxis, dir(lamp -> point)))
//              win   = ramp down over the last 40% of the beam length
//              att   = 1 / (1 + |lamp - P|^2 / attRef)
//              NdotL = max(dot(N, L), 0)
//              surface += colour * power * spot * win * att * NdotL
//
//   VOLUME   the "light in the air": the part of the lamp's glowing sphere this pixel's view ray
//            crosses, up to the first opaque surface:
//
//              chord  = length of the ray inside the sphere (radius R)
//              tau    = (chord/R) * cone * window * phase * trans * power * falloff * 0.125
//
//   COMPOSITE  (dst = src: the json blend is one/zero, i.e. a replace - see light_scatter.json)
//
//              added = volumeSum * VolumeGain + surfaceSum * (scene * SurfAlbedo + SurfFloor)
//              lit   = scene + (1 - exp(-added * strength)) * (1 - 0.5 * scene)
//
//            where `scene` is this very pixel, copied before the draw. With `added == 0` that is
//            exactly `scene`, so an empty lamp list or a wrong reconstruction matrix can only leave
//            the frame as it was - the pass cannot black the screen out.
//
// uSurface.z == 0 skips the whole surface block (uniform branch: coherent, so the per-lamp normal /
// spot / N-dot-L work is not executed at all); the volume glow and the composite stay exactly the
// same. That is the "true illumination off, air haze on" switch.
//
// Sky pixels (depth == 1) never get a surface term: there is no surface there. They still get the
// volume term, which is the case the effect exists for (a head light shining into the night sky).

uniform sampler2D Sampler0;                 // scene depth copy, window space [0,1] in .r
uniform sampler2D Sampler1;                 // scene colour copy, same window space (albedo)
uniform mat4 uInvViewProj;                  // inverse(projection * view), camera relative
uniform vec4 uParams;                       // x = volume gain, y = extinction/block, z = max ray, w = phase g
uniform vec4 uCone;                         // x = cos(inner), y = cos(outer)
uniform vec4 uSurface;                      // x = strength k, y = attenuation ref (blocks^2), z = surface on, w = coverage probe
uniform int uCount;                         // lamps actually filled in
uniform int uTest;                          // 1 = /mtr_lte lighttest: fixed red output, no maths at all
uniform vec4 uLightPos[16];                 // xyz = lamp, camera relative; w = glow radius R
uniform vec4 uLightDir[16];                 // xyz = unit heading; w = beam length
uniform vec4 uLightColor[16];               // rgb = colour; w = intensity

in vec2 vUv;
out vec4 fragColor;

const float PHASE_MAX = 4.0;
// Surface calibration: a lit surface adds light in proportion to its own albedo (irradiance *
// albedo), with a small floor so very dark asphalt still shows a pool instead of vanishing.
// 8.0 / 0.05 puts a head light 2 blocks from a 0.15-albedo platform at ~+0.23 and 5 blocks out at
// ~+0.10 - clearly a lit floor, not a white sheet (tools/scatter-math-check.js prints the numbers).
const float SURF_ALBEDO = 8.0;
const float SURF_FLOOR = 0.05;

float smooth01(float x) {
    float t = clamp(x, 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

void main() {
    // 取证：/mtr_lte lighttest 打开时，这一整个 pass 只输出一块纯红 —— 不读深度、不看灯数、
    // 不做任何数学。于是「屏幕上有没有红」只取决于「程序绑定 + uniform 上传 + 这块全屏四边形 +
    // 颜色附件」这条链，与模型和参数完全无关。见 dev.js.mtrheadlight.LightTest。
    if (uTest == 1) {
        fragColor = vec4(1.0, 0.0, 0.0, 1.0);
        return;
    }

    vec2 ndc = vUv * 2.0 - 1.0;
    float sceneDepth = texture(Sampler0, vUv).r;

    // The near/far pair gives the ray direction; the depth tells where the ray stops. With
    // depth == 1 there is no surface (sky) and the ray runs to MaxRay, so a beam drawn against the
    // sky still scatters - the case where the effect matters most at night.
    vec4 nearClip = uInvViewProj * vec4(ndc, -1.0, 1.0);
    vec4 farClip = uInvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 nearPoint = nearClip.xyz / nearClip.w;
    vec3 farPoint = farClip.xyz / farClip.w;
    vec3 rd = normalize(farPoint - nearPoint);

    float maxRay = uParams.z;
    bool hasSurface = sceneDepth < 0.9999995;
    float tHit = maxRay;
    if (hasSurface) {
        vec4 atDepth = uInvViewProj * vec4(ndc, sceneDepth * 2.0 - 1.0, 1.0);
        if (abs(atDepth.w) > 1.0e-7) {
            tHit = clamp(length(atDepth.xyz / atDepth.w), 0.02, maxRay);
        } else {
            hasSurface = false;
        }
    }

    // World position of the pixel, camera relative (the space the lamps are already in).
    vec3 P = rd * tHit;

    // Face normal from the screen-space derivatives of that position - no geometry buffer, no
    // extra pass, nothing a mod without a render pipeline could not afford. The derivatives are
    // taken unconditionally (they must be evaluated in uniform control flow) and only *used* where
    // there is a surface. The flip makes the normal face the eye, which sits at the origin of this
    // space; without it the cross product turns every floor away from the light.
    vec3 dpx = dFdx(P);
    vec3 dpy = dFdy(P);
    vec3 nrm = cross(dpx, dpy);
    float nrmLen = length(nrm);
    nrm = nrmLen > 1.0e-9 ? nrm / nrmLen : vec3(0.0, 1.0, 0.0);
    if (dot(nrm, P) > 0.0) {
        nrm = -nrm;
    }
    // At a silhouette one pixel of this 2x2 quad sits on the near object and the other on whatever
    // is behind it, so the "derivative" is metres instead of millimetres and the resulting normal is
    // an artefact that would paint a bright rim around every edge. A real surface, even at a grazing
    // angle, never moves a quarter of its own distance between two neighbouring pixels.
    bool normalValid = max(length(dpx), length(dpy)) < max(0.5, tHit * 0.25);

    bool computeSurface = uSurface.z > 0.5 && hasSurface && normalValid;
    float strength = max(uSurface.x, 1.0e-3);
    float attRef = max(uSurface.y, 1.0e-3);

    float gain = uParams.x;
    float extinction = uParams.y;
    float g = clamp(uParams.w, 0.0, 0.92);
    float g2 = g * g;
    float cosInner = uCone.x;
    float cosOuter = uCone.y;

    vec3 volumeSum = vec3(0.0);
    vec3 surfaceSum = vec3(0.0);
    float coverage = 0.0;

    for (int i = 0; i < 16; i++) {
        if (i >= uCount) {
            break;
        }

        vec4 pos = uLightPos[i];
        vec4 dir = uLightDir[i];
        vec4 tint = uLightColor[i];

        float power = tint.w;
        if (power <= 1.0e-4) {
            continue;
        }
        vec3 lamp = pos.xyz;
        float radius = max(pos.w, 0.05);
        vec3 axis = normalize(dir.xyz);
        float beamLength = max(dir.w, 0.5);

        // ---------------- surface term ----------------
        // Before the volume cull on purpose: a surface can be lit by a lamp whose glow sphere this
        // pixel's ray never enters (the lamp is above and behind the line of sight, and the floor in
        // front of you is lit) - that is the normal case, not the exception.
        if (computeSurface) {
            vec3 toLamp = lamp - P;
            float d = length(toLamp);
            if (d > 1.0e-3) {
                vec3 L = toLamp / d;
                // Cone gate: the beam axis vs the direction the lamp actually sends light along.
                float cosAngle = dot(axis, -L);
                float spot = smooth01((cosAngle - cosOuter) / max(cosInner - cosOuter, 1.0e-3));
                // Distance window: no light past the end of the beam.
                float win = 1.0 - smooth01((d - beamLength * 0.6) / max(beamLength * 0.4, 1.0e-3));
                // Inverse-square-ish irradiance at the surface.
                float att = 1.0 / (1.0 + d * d / attRef);
                float ndotl = max(dot(nrm, L), 0.0);
                float lit = spot * win * att * ndotl;
                if (lit > 0.0) {
                    surfaceSum += tint.rgb * (power * lit);
                    coverage = max(coverage, min(lit * power, 1.0));
                }
            }
        }

        // ---------------- volume term ----------------
        // Closest approach of this pixel's ray to the lamp, then the chord of its sphere.
        float tClosest = dot(lamp, rd);
        float perp2 = dot(lamp, lamp) - tClosest * tClosest;
        if (perp2 >= radius * radius) {
            continue;                            // the ray misses this lamp's volume
        }
        float halfChord = sqrt(max(radius * radius - perp2, 0.0));
        float t0 = max(tClosest - halfChord, 0.0);
        float t1 = min(tClosest + halfChord, tHit);
        if (t1 <= t0) {
            continue;                            // behind the eye, or a surface cuts it all off
        }
        float chord = t1 - t0;

        // Midpoint of the visible chord: where the scattering is evaluated.
        vec3 mid = rd * (0.5 * (t0 + t1));
        vec3 toMid = mid - lamp;
        float dist = length(toMid);
        vec3 midDir = dist > 1.0e-4 ? toMid / dist : axis;

        // Cone gate: the lamp only lights the air it is pointed at. The halo term keeps the air
        // immediately around the lamp glowing in every direction (that is the flare's own volume).
        float cosAngle = dot(midDir, axis);
        float cone = smooth01((cosAngle - cosOuter) / max(cosInner - cosOuter, 1.0e-3));
        float halo = 1.0 - smooth01(dist / radius);
        cone = max(cone, halo);

        // Axial window: nothing past the end of the beam, so the beam length is a real cut.
        float axial = dot(toMid, axis);
        float window = 1.0 - smooth01((axial - beamLength * 0.6) / max(beamLength * 0.4, 1.0e-3));
        window = max(window, halo);

        // Forward scattering: the light travels along the axis, the camera looks along -rd.
        float cosTheta = clamp(-dot(axis, rd), -1.0, 1.0);
        float denom = max(1.0 + g2 - 2.0 * g * cosTheta, 1.0e-3);
        float phase = min((1.0 - g2) / pow(denom, 1.5), PHASE_MAX);

        // Attenuation between the lamp and the scattering point.
        float trans = exp(-extinction * dist);

        // Inverse-square-ish illumination of the medium: the 9 (3 blocks squared) keeps the near
        // field from exploding - at dist = 3 the medium is at half strength, at 6 a fifth.
        float falloff = 1.0 / (1.0 + dist * dist / 9.0);

        // 0.125 is the one calibration constant of the volume model: with chord/R in [0,2], a phase
        // near 4 head-on and falloff 1 at the lamp, one lamp staring at the camera lands at
        // tau = 1, i.e. lit = 1 - exp(-1.6) = 0.80.
        volumeSum += tint.rgb * ((chord / radius) * cone * window * phase * trans * power
                * falloff * 0.125);
    }

    // ---- composite: replace the screen with scene + what the lamps add ----
    vec3 scene = texture(Sampler1, vUv).rgb;
    // The surface term is scaled by the albedo of the pixel it lands on, so a dark floor answers in
    // its own colour instead of washing out in the lamp's.
    vec3 added = volumeSum * gain + surfaceSum * (scene * SURF_ALBEDO + SURF_FLOOR);
    vec3 lit = scene + (1.0 - exp(-added * strength)) * (1.0 - 0.5 * scene);
    // Alpha carries the coverage probe for /mtr_lte scatter status. The window blit ignores alpha,
    // so writing it costs nothing on a normal frame.
    float alpha = uSurface.w > 0.5 ? step(1.0e-4, coverage) : 1.0;
    fragColor = vec4(clamp(lit, 0.0, 1.0), alpha);
}
