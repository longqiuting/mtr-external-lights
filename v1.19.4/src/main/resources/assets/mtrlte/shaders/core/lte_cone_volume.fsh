#version 150

// Depth-aware beam cone - fragment stage.
//
// For every fragment the view ray is intersected analytically with the finite cone
// (apex + axis + half angle, truncated at `length`), then the interval inside the volume is
// marched in `Steps` samples. Each sample is projected back to screen space and compared
// against the copied scene depth, so a wall / car body / pole really does cut the beam
// instead of the beam painting over it. Samples behind the scene are dropped, the rest are
// accumulated with
//
//     radial    (1 - r^2)^2      r = radial / (axial * tanHalf), 0 on the axis, 1 at the rim
//     axial     exp(-2u) * (1-u) u = axial / length, exactly 0 at the tip
//     length    dt               so the result is an integral, i.e. it depends on how much
//                                air the ray actually crosses (thick down the axis, thin at
//                                the silhouette) - that is the "light shaft in the air" read
//
// Alpha = 1 - exp(-sum * density), so brightness saturates instead of clipping.

uniform sampler2D Sampler0;                 // scene depth copy, window space [0,1] in .r
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

uniform vec4 BeamApex;                      // xyz = cone apex, camera-relative
uniform vec4 BeamAxis;                      // xyz = unit axis (apex -> tip)
uniform vec4 BeamParams;                    // x = length, y = tan(halfAngle), z = steps, w = density

in vec4 vertexColor;
in vec3 camPos;

out vec4 fragColor;

const float MIN_T = 0.02;                   // start just past the eye
const float MAX_STEPS = 16.0;
const float DEPTH_BIAS_BLOCKS = 0.75;       // tolerate this much geometric slack (hides banding)

float hash12(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec3 rd = normalize(camPos);
    vec3 apex = BeamApex.xyz;
    vec3 axis = normalize(BeamAxis.xyz);
    float len = BeamParams.x;
    float tanHalf = max(BeamParams.y, 1.0e-4);
    float steps = clamp(BeamParams.z, 4.0, MAX_STEPS);
    float density = BeamParams.w;

    // Ray / infinite cone: |v + t*rd|^2 - tan^2 * dot(v + t*rd, axis)^2 = 0, v = -apex
    vec3 v = -apex;
    float kd = dot(rd, axis);
    float cd = dot(v, axis);
    float t2 = tanHalf * tanHalf;

    float a2 = 1.0 - t2 * kd * kd;
    float a1 = 2.0 * (dot(v, rd) - t2 * kd * cd);
    float a0 = dot(v, v) - t2 * cd * cd;

    if (a2 <= 1.0e-6) {
        discard;                            // cone wider than 45 deg: not a beam, skip
    }
    float disc = a1 * a1 - 4.0 * a2 * a0;
    if (disc <= 0.0) {
        discard;                            // ray misses the infinite cone
    }
    float sq = sqrt(disc);
    float r0 = (-a1 - sq) / (2.0 * a2);
    float r1 = (-a1 + sq) / (2.0 * a2);

    float tEnter = max(MIN_T, r0);
    float tExit = r1;

    // Truncate at the lamp (axial >= 0) and at the tip (axial <= length).
    // axial(t) = cd + t * kd
    if (kd > 1.0e-5) {
        tEnter = max(tEnter, -cd / kd);
        tExit = min(tExit, (len - cd) / kd);
    } else if (kd < -1.0e-5) {
        tEnter = max(tEnter, (len - cd) / kd);
        tExit = min(tExit, -cd / kd);
    } else if (cd < 0.0 || cd > len) {
        discard;                            // ray parallel to the axis, outside the length
    }
    if (tExit <= tEnter) {
        discard;
    }

    // Near plane of the projection, used to turn "1 block of slack" into window-depth units
    // (d(window z)/d(blocks) ~= near / distance^2). For the standard perspective matrix
    // near = m32 / (m22 - 1); the guard keeps a broken matrix from producing garbage.
    float nearP = ProjMat[3][2] / (ProjMat[2][2] - 1.0);
    if (!(nearP > 0.0) || nearP > 1.0) {
        nearP = 0.05;
    }

    float dt = (tExit - tEnter) / steps;
    float jitter = hash12(gl_FragCoord.xy);     // static per pixel: breaks up banding, no shimmer
    float sum = 0.0;

    for (int i = 0; i < 16; i++) {
        if (float(i) >= steps) {
            break;
        }
        float t = tEnter + dt * (float(i) + jitter);
        vec3 p = rd * t;

        float axial = cd + t * kd;
        if (axial <= 1.0e-3 || axial > len) {
            continue;
        }
        vec3 radialVec = p - apex - axis * axial;
        float rn = length(radialVec) / (axial * tanHalf);
        float prof = 1.0 - rn * rn;
        if (prof <= 0.0) {
            continue;
        }
        prof *= prof;
        float u = axial / len;
        prof *= exp(-2.0 * u) * (1.0 - u);
        if (prof <= 1.0e-5) {
            continue;
        }

        // Occlusion: project the sample and compare with the scene depth.
        vec4 clip = ProjMat * ModelViewMat * vec4(p, 1.0);
        if (clip.w <= 0.0) {
            continue;
        }
        vec2 uv = (clip.xy / clip.w) * 0.5 + 0.5;
        float pz = (clip.z / clip.w) * 0.5 + 0.5;
        float vis = 1.0;
        if (uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0) {
            float scene = texture(Sampler0, uv).r;
            float bias = DEPTH_BIAS_BLOCKS * nearP / max(t * t, 0.25);
            vis = smoothstep(0.0, bias, scene - pz);
        }
        sum += prof * vis * dt;
    }

    float alpha = 1.0 - exp(-sum * density);
    alpha *= vertexColor.a;
    if (alpha < 0.002) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb, alpha);
}
