#version 150

// Depth-aware beam cone - vertex stage.
//
// The mesh is only a *coverage proxy*: it rasterises the cone's silhouette so that every
// pixel the volume could cover gets exactly one fragment (depth test on, so the nearest
// proxy surface wins). The actual light is integrated in the fragment stage along the view
// ray, so the geometry carries nothing but the camera-relative position of the proxy shell
// and the beam's colour / alpha budget.

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;
out vec3 camPos;

void main() {
    camPos = Position;                      // camera-relative, camera is at the origin
    vertexColor = Color;                    // rgb = beam colour, a = alpha budget
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
