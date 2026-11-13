#version 150

// Screen-space lamp scattering - vertex stage.
//
// The geometry is a single full-screen quad in NDC; it carries nothing but where it is on screen.
// The fragment stage rebuilds the view ray from the depth buffer and does all the work, so there is
// no per-lamp geometry, no vertex colour and no matrix stack to keep in sync.

in vec3 Position;

out vec2 vUv;

void main() {
    // NDC [-1,1] -> texture space [0,1]. The depth copy is a plain window-space texture, so v=0 is
    // the bottom row on both sides and no flip is needed.
    vUv = Position.xy * 0.5 + 0.5;
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
