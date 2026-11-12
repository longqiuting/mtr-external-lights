// 离线核对：复刻 DepOnWallRenderer.applyScreenTransform 的矩阵链，把 "00:19" 投影到
// 「站在牌子正前方（北侧）朝南看」的视角上，用字符画出来。
// 用法: java DepOrient.java <mirror:true|false> <anchorX:2|14>
public class DepOrient {

    // ---- JOML 列主序 4x4，语义与 Matrix4f 一致（multiply = 后乘，顶点按列向量乘） ----
    static float[] I() { float[] m = new float[16]; m[0] = m[5] = m[10] = m[15] = 1; return m; }

    static float[] mul(float[] a, float[] b) { // a * b
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) for (int row = 0; row < 4; row++) {
            float s = 0;
            for (int k = 0; k < 4; k++) s += a[k * 4 + row] * b[c * 4 + k];
            r[c * 4 + row] = s;
        }
        return r;
    }

    static float[] T(float x, float y, float z) { float[] m = I(); m[12] = x; m[13] = y; m[14] = z; return m; }

    static float[] S(float x, float y, float z) { float[] m = I(); m[0] = x; m[5] = y; m[10] = z; return m; }

    static float[] Ry(float deg) {
        double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
        float[] m = I();
        m[0] = (float) c; m[8] = (float) s;
        m[2] = (float) -s; m[10] = (float) c;
        return m;
    }

    static float[] Rz(float deg) {
        double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
        float[] m = I();
        m[0] = (float) c; m[4] = (float) -s;
        m[1] = (float) s; m[5] = (float) c;
        return m;
    }

    static float[] p(float[] m, float x, float y, float z) { // 返回世界 (x,y,z)
        return new float[]{
                m[0] * x + m[4] * y + m[8] * z + m[12],
                m[1] * x + m[5] * y + m[9] * z + m[13],
                m[2] * x + m[6] * y + m[10] * z + m[14],
        };
    }

    // ---- 变换链（facing = north，方块 y 角 = 0） ----
    static float[] chain(boolean mirror, float anchorX) {
        float[] m = I();
        m = mul(m, T(0.5F, 0.0F, 0.5F));
        m = mul(m, Ry(0.0F));                                    // blockRotation(north) = 0
        m = mul(m, T(anchorX - 0.5F, 12.0F / 16.0F, 11.96F / 16.0F - 0.5F));
        m = mul(m, Rz(180.0F));
        if (mirror) m = mul(m, S(-1, 1, 1));
        m = mul(m, S(1 / 128.0F, 1 / 128.0F, 1 / 128.0F));
        return m;
    }

    // ---- 七段几何（照 DepOnWallDigits 的常量与点序） ----
    static final float ASPECT = 0.50F, THICK = 0.13F, GAP = 0.08F, COLON = 0.18F;
    static final int A = 1, B = 2, C = 4, D = 8, E = 16, F = 32, G = 64;
    static final int[] GL = {
            A | B | C | D | E | F, B | C, A | B | G | E | D, A | B | G | C | D, F | G | B | C,
            A | F | G | C | D, A | F | G | E | C | D, A | B | C, A | B | C | D | E | F | G, A | B | C | D | F | G};

    static final java.util.List<float[][]> TRIS = new java.util.ArrayList<>(); // 每个三角形: 3 x (x,y)

    static void quad(float[] a, float[] b, float[] c, float[] d) {
        TRIS.add(new float[][]{a, b, c});
        TRIS.add(new float[][]{a, c, d});
    }

    static void hex(float[][] pts) {
        float cx = 0, cy = 0;
        for (float[] q : pts) { cx += q[0]; cy += q[1]; }
        cx /= 6; cy /= 6;
        for (int i = 0; i < 6; i++) quad(pts[i], pts[(i + 1) % 6], new float[]{cx, cy}, new float[]{cx, cy});
    }

    static void hseg(float x0, float x1, float y, float t) {
        float h = t * 0.5F;
        hex(new float[][]{{x0, y}, {x0 + h, y + h}, {x1 - h, y + h}, {x1, y}, {x1 - h, y - h}, {x0 + h, y - h}});
    }

    static void vseg(float x, float y0, float y1, float t) {
        float h = t * 0.5F;
        hex(new float[][]{{x, y0}, {x - h, y0 + h}, {x - h, y1 - h}, {x, y1}, {x + h, y1 - h}, {x + h, y0 + h}});
    }

    static void build(String text) {
        float h = 1.0F;
        float dw = h * ASPECT, cw = h * COLON, gap = h * GAP, t = h * THICK;
        float total = 0;
        for (int i = 0; i < text.length(); i++) { if (i > 0) total += gap; total += text.charAt(i) == ':' ? cw : dw; }
        // 与渲染器一致：按可用宽高取字高并居中（这里直接用比例，取字高 = 40px 方便看）
        h = 40.0F;
        float scaleH = h;                          // 字高 40 px
        dw = scaleH * ASPECT; cw = scaleH * COLON; gap = scaleH * GAP; t = scaleH * THICK;
        total = 0;
        for (int i = 0; i < text.length(); i++) { if (i > 0) total += gap; total += text.charAt(i) == ':' ? cw : dw; }
        float left = (96.0F - total) * 0.5F, top = (64.0F - scaleH) * 0.5F;
        float x = left;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (i > 0) x += gap;
            if (c == ':') {
                float size = t * 1.15F, ccx = x + cw * 0.5F, hh = size * 0.5F;
                for (float yy : new float[]{top + scaleH * 0.30F, top + scaleH * 0.70F}) {
                    quad(new float[]{ccx - hh, yy - hh}, new float[]{ccx - hh, yy + hh},
                         new float[]{ccx + hh, yy + hh}, new float[]{ccx + hh, yy - hh});
                }
                x += cw;
            } else {
                int mask = GL[c - '0'];
                float half = t * 0.5F, x0 = x + half, x1 = x + dw - half;
                float y0 = top + half, y1 = top + scaleH - half, ym = top + scaleH * 0.5F;
                if ((mask & A) != 0) hseg(x0, x1, y0, t);
                if ((mask & D) != 0) hseg(x0, x1, y1, t);
                if ((mask & G) != 0) hseg(x0, x1, ym, t);
                if ((mask & F) != 0) vseg(x0, y0, ym, t);
                if ((mask & B) != 0) vseg(x1, y0, ym, t);
                if ((mask & E) != 0) vseg(x0, ym, y1, t);
                if ((mask & C) != 0) vseg(x1, ym, y1, t);
                x += dw;
            }
        }
    }

    public static void main(String[] args) {
        boolean mirror = Boolean.parseBoolean(args[0]);
        float anchorX = Float.parseFloat(args[1]) / 16.0F;
        float[] m = chain(mirror, anchorX);

        // 1) 局部轴向的世界去向
        float[] o = p(m, 0, 0, 0), ex = p(m, 1, 0, 0), ey = p(m, 0, 1, 0), ez = p(m, 0, 0, 1);
        System.out.printf("local origin  -> (%.4f, %.4f, %.4f)%n", o[0], o[1], o[2]);
        System.out.printf("local +X(1px) -> d(%.5f, %.5f, %.5f)%n", ex[0] - o[0], ex[1] - o[1], ex[2] - o[2]);
        System.out.printf("local +Y(1px) -> d(%.5f, %.5f, %.5f)%n", ey[0] - o[0], ey[1] - o[1], ey[2] - o[2]);
        System.out.printf("local +Z(1px) -> d(%.5f, %.5f, %.5f)%n", ez[0] - o[0], ez[1] - o[1], ez[2] - o[2]);
        System.out.printf("screen quad: (0,0)=(%.4f,%.4f,%.4f)  (96,64)=(%.4f,%.4f,%.4f)%n",
                p(m, 0, 0, 0)[0], p(m, 0, 0, 0)[1], p(m, 0, 0, 0)[2],
                p(m, 96, 64, 0)[0], p(m, 96, 64, 0)[1], p(m, 96, 64, 0)[2]);

        build("00:19");

        // 2) 观察者站北侧朝南(+Z)看：屏幕右 = -X，屏幕下 = -Y
        final int COLS = 60, ROWS = 26;
        final float X0 = 0.0F, X1 = 1.0F, Y0 = 0.25F, Y1 = 0.75F;   // 只看屏幕附近
        char[][] buf = new char[ROWS][COLS];
        for (char[] row : buf) java.util.Arrays.fill(row, '.');

        for (float[][] tri : TRIS) {
            float[][] s = new float[3][2];
            for (int i = 0; i < 3; i++) {
                float[] w = p(m, tri[i][0], tri[i][1], 0);
                s[i][0] = (X1 - w[0]) / (X1 - X0) * COLS;   // 屏幕右 = -X
                s[i][1] = (Y1 - w[1]) / (Y1 - Y0) * ROWS;   // 屏幕下 = -Y
            }
            float det = (s[1][0] - s[0][0]) * (s[2][1] - s[0][1]) - (s[2][0] - s[0][0]) * (s[1][1] - s[0][1]);
            if (Math.abs(det) < 1e-9F) continue;
            int minX = Math.max(0, (int) Math.floor(Math.min(s[0][0], Math.min(s[1][0], s[2][0]))));
            int maxX = Math.min(COLS - 1, (int) Math.ceil(Math.max(s[0][0], Math.max(s[1][0], s[2][0]))));
            int minY = Math.max(0, (int) Math.floor(Math.min(s[0][1], Math.min(s[1][1], s[2][1]))));
            int maxY = Math.min(ROWS - 1, (int) Math.ceil(Math.max(s[0][1], Math.max(s[1][1], s[2][1]))));
            for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5F, py = y + 0.5F;
                float w0 = ((s[1][0] - px) * (s[2][1] - py) - (s[2][0] - px) * (s[1][1] - py)) / det;
                float w1 = ((s[2][0] - px) * (s[0][1] - py) - (s[0][0] - px) * (s[2][1] - py)) / det;
                float w2 = 1 - w0 - w1;
                if (w0 >= -0.001F && w1 >= -0.001F && w2 >= -0.001F) buf[y][x] = '#';
            }
        }
        System.out.println("\n---- 站在北侧朝南看到的屏幕（# = 数码管） ----");
        for (char[] row : buf) System.out.println(new String(row));
    }
}
