#version 150

// Screen-space lamp light - fragment stage.
//
// One pass over the visible frame, ONE term per lamp (the light the lamp throws onto a surface), then
// one composite that replaces the screen.
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
//   COMPOSITE  (dst = src: the json blend is one/zero, i.e. a replace - see light_scatter.json)
//
//              added = surfaceSum * (scene * SurfAlbedo + SurfFloor)
//              lit   = scene + (1 - exp(-added * strength)) * (1 - 0.5 * scene)
//
//            where `scene` is this very pixel, copied before the draw. With `added == 0` that is
//            exactly `scene`, so an empty lamp list or a wrong reconstruction matrix can only leave
//            the frame as it was - the pass cannot black the screen out.
//
// The VOLUME term (the "light in the air": chord of the lamp's glow sphere along the view ray,
// Henyey-Greenstein phase, transmittance) was REMOVED in this version - the user judged the haze it
// produced to be worse than nothing. Only the surface term is left, so `scatterStrength` now scales
// the surface light alone. docs/screen-space-scatter.md records the removal.
//
// uSurface.z == 0 skips the whole surface block (uniform branch: coherent, so the per-lamp normal /
// spot / N-dot-L work is not executed at all) and the pass then writes `scene` back unchanged - the
// "lamp lighting off" switch.
//
// Sky pixels (depth == 1) have no surface, so they get nothing: with the volume term gone there is
// no term left that could light empty air.

uniform sampler2D Sampler0;                 // scene depth copy, window space [0,1] in .r
uniform sampler2D Sampler1;                 // scene colour copy, same window space (albedo)
uniform mat4 uInvViewProj;                  // inverse(projection * view), camera relative
uniform float uMaxRay;                      // longest view ray, blocks (also the cut for sky pixels)
uniform vec4 uCone;                         // x = cos(inner), y = cos(outer)
uniform vec4 uSurface;                      // x = strength k, y = attenuation ref (blocks^2), z = surface on, w = coverage probe
uniform int uCount;                         // lamps actually filled in
uniform int uTest;                          // 1 = /mtr_lte lighttest: fixed red output, no maths at all
uniform int uDebug;                         // 0 = off, 1 = surface (N.L/cone/att), 2 = normal, 3 = cone
uniform vec4 uLightPos[16];                 // xyz = lamp, camera relative; w = glow radius R
uniform vec4 uLightDir[16];                  // xyz = uLightDir = 灯照射方向（朝车外，单位向量）; w = beam length
uniform vec4 uLightColor[16];               // rgb = colour; w = intensity
// 三个数组由 ScatterProgram 在 bind 之后用 glUniform4fv 一次上传（1.20.1 的 core shader json
// 表达不了 vec4[]：那里只有 int / float / matrixN，写成 float 会被发成 glUniform1fv 而整条失效）。

in vec2 vUv;
out vec4 fragColor;

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

    // The near/far pair gives the ray direction; the depth tells where the ray stops.
    vec4 nearClip = uInvViewProj * vec4(ndc, -1.0, 1.0);
    vec4 farClip = uInvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 nearPoint = nearClip.xyz / nearClip.w;
    vec3 farPoint = farClip.xyz / farClip.w;
    vec3 rd = normalize(farPoint - nearPoint);

    float maxRay = uMaxRay;
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

    float cosInner = uCone.x;
    float cosOuter = uCone.y;

    vec3 surfaceSum = vec3(0.0);
    float coverage = 0.0;

    /*
     * ---- debug 通道的累加器（uDebug != 0 时才写）----
     * 每个像素记「所有灯里最大的那一份」，所以屏幕上一块非零的区域就是「至少有一盏灯在这里
     * 通过了这一项」。三项各自独立，看一眼就知道是哪一项把结果乘成 0：
     *   dbgNdotL  法线是否朝向灯（max(dot(N,L),0)）
     *   dbgSpot   是否落在灯锥里（smoothstep(cosOuter, cosInner, cosAngle)）
     *   dbgAtt    距离衰减 × 锥内距离窗口（灯泡真的照得到这么远吗）
     */
    float dbgNdotL = 0.0;
    float dbgSpot = 0.0;
    float dbgAtt = 0.0;
    float dbgCos = -1.0;
    float dbgLamps = 0.0;

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
        /*
         * 朝向。退化方向（dir == 0）绝不能让 normalize 产出 NaN：NaN 进了 axis 之后锥、法线、
         * 距离全是 NaN，整条 pass 就变成「什么都没画」，而且日志里每个字段都是正常的数 ——
         * 这是「表面项完全看不到」最难查的一种。退化成「朝相机」而不是报错。
         */
        vec3 axis = dir.xyz;
        float axisLen = length(axis);
        if (axisLen > 1.0e-4) {
            axis = axis / axisLen;
        } else {
            float lampLen = length(lamp);
            axis = lampLen > 1.0e-4 ? -lamp / lampLen : vec3(0.0, 0.0, -1.0);
        }
        float beamLength = max(dir.w, 0.5);

        // ---------------- debug channel ----------------
        // 独立于 uSurface.z：表面项关掉时也要能回答「到底是哪一项是 0」。
        if (uDebug != 0) {
            vec3 toLampD = lamp - P;
            float dD = length(toLampD);
            if (dD > 1.0e-3) {
                vec3 LD = toLampD / dD;                 // P -> lamp（N·L 用的方向）
                vec3 toPointD = -LD;                    // lamp -> P（灯发出的光前进的方向）
                float cosD = dot(axis, toPointD);       // 扇面轴就是 +axis
                float spotD = smooth01((cosD - cosOuter) / max(cosInner - cosOuter, 1.0e-3));
                float attD = 1.0 / (1.0 + dD * dD / attRef);
                float winD = 1.0 - smooth01((dD - beamLength * 0.6)
                        / max(beamLength * 0.4, 1.0e-3));
                dbgCos = max(dbgCos, cosD);
                dbgSpot = max(dbgSpot, spotD);
                dbgNdotL = max(dbgNdotL, max(dot(nrm, LD), 0.0));
                dbgAtt = max(dbgAtt, attD * winD);
                dbgLamps += 1.0;
            }
        }

        // ---------------- surface term (the only term left) ----------------
        if (computeSurface) {
            vec3 toLamp = lamp - P;
            float d = length(toLamp);
            if (d > 1.0e-3) {
                vec3 L = toLamp / d;
                // 扇面轴 = +axis，也就是「从灯里出来的光走的方向」（uLightDir 约定：朝车外、单位向量）。
                // 所以这里比的是 axis 与 lamp -> P，而不是 P -> lamp：后者会把整个锥体转到灯背后，
                // 于是除灯正对相机的那一瞬之外 N·L 与锥两项同时为 0（「一点照亮都没有」）。
                vec3 toPoint = -L;
                float cosAngle = dot(axis, toPoint);
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
    }

    /*
     * ---- 三个 debug 输出（/mtr_lte scatter debug surface|normal|cone）----
     * 全部是纯色可视化，直接 replace 屏幕（json 里没有 blend 段 = dst = src），所以「有没有颜色」
     * 只取决于这一项本身是不是 0，与亮度、色调、参数都没有关系。
     */
    if (uDebug == 1) {
        // surface：R = 有 N·L，G = 在锥内，B = 有距离衰减。三色都亮 = 三项都通过；
        // 红亮绿黑 = 法线对了但灯根本没照到这里（锥角/朝向反了）；绿亮红黑 = 锥对了但法线朝下。
        fragColor = vec4(clamp(dbgNdotL, 0.0, 1.0), clamp(dbgSpot, 0.0, 1.0),
                clamp(dbgAtt, 0.0, 1.0), 1.0);
        return;
    }
    if (uDebug == 2) {
        // normal：RGB = 法线编码（地面朝上 → 绿；墙 → 红/蓝），黑 = 天空（没有表面），
        // 洋红 = 有几何但法线被判成轮廓伪影（normalValid=false，那些像素永远不会有表面项）。
        if (!hasSurface) {
            fragColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
        if (!normalValid) {
            fragColor = vec4(1.0, 0.0, 1.0, 1.0);
            return;
        }
        fragColor = vec4(nrm * 0.5 + 0.5, 1.0);
        return;
    }
    if (uDebug == 3) {
        // cone：R = 灯轴与「灯→像素」的余弦（0.5 = 90°，1 = 正对灯轴），G = smoothstep 之后的锥内
        // 权重（0 = 在 57° 外），B = 这一像素至少被一盏灯看到（1）。B=0 说明灯列表是空的/像素在
        // 所有灯后面，跟锥角无关。
        fragColor = vec4(clamp(max(dbgCos, 0.0), 0.0, 1.0), clamp(dbgSpot, 0.0, 1.0),
                dbgLamps > 0.0 ? 1.0 : 0.0, 1.0);
        return;
    }

    // ---- composite: replace the screen with scene + the light the lamps add ----
    vec3 scene = texture(Sampler1, vUv).rgb;
    // The surface term is scaled by the albedo of the pixel it lands on, so a dark floor answers in
    // its own colour instead of washing out in the lamp's. `strength` (scatterStrength) is the only
    // gain now that the volume term is gone.
    vec3 added = surfaceSum * (scene * SURF_ALBEDO + SURF_FLOOR);
    vec3 lit = scene + (1.0 - exp(-added * strength)) * (1.0 - 0.5 * scene);
    // Alpha carries the coverage probe for /mtr_lte scatter status. The window blit ignores alpha,
    // so writing it costs nothing on a normal frame.
    float alpha = uSurface.w > 0.5 ? step(1.0e-4, coverage) : 1.0;
    fragColor = vec4(clamp(lit, 0.0, 1.0), alpha);
}
