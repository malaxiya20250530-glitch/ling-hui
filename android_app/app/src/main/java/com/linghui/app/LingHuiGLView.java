package com.linghui.app;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** OpenGL ES 2.0 3D 角色渲染 — 球体 + Phong 光照 + 动画状态 */
public class LingHuiGLView extends GLSurfaceView implements ICharacterView {

    private final LingHuiRenderer renderer;

    public LingHuiGLView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        // 透明背景
        setZOrderOnTop(true);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        renderer = new LingHuiRenderer();
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    // ---------- 动画状态控制 ----------
    public void onInteract()   { renderer.setMood(Mood.EXCITED); }
    public void onReplyReceived() { renderer.setMood(Mood.HAPPY); }
    public void onIdle()       { renderer.setMood(Mood.IDLE); }

    enum Mood { IDLE, EXCITED, HAPPY }

    private static class LingHuiRenderer implements GLSurfaceView.Renderer {

        // 球体顶点数据
        private FloatBuffer vertexBuffer;
        private FloatBuffer normalBuffer;
        private int vertexCount;

        // 着色器
        private int program;
        private int uMVPMatrix, uMVMatrix, uLightPos, uLightColor, uObjectColor, uTime;
        private int aPosition, aNormal;

        // 矩阵
        private final float[] modelMatrix = new float[16];
        private final float[] viewMatrix = new float[16];
        private final float[] projMatrix = new float[16];
        private final float[] mvpMatrix = new float[16];

        // 动画
        private float rotationAngle;
        private Mood mood = Mood.IDLE;
        private float pulseScale = 1.0f;
        private long moodStartTime;

        // 12色调色板 + 定时渐变
        private static final float[][] PALETTE = {
            {1.00f, 0.45f, 0.45f}, {1.00f, 0.65f, 0.30f},
            {1.00f, 0.88f, 0.35f}, {0.55f, 0.90f, 0.45f},
            {0.35f, 0.80f, 0.75f}, {0.35f, 0.65f, 1.00f},
            {0.50f, 0.45f, 1.00f}, {0.85f, 0.45f, 1.00f},
            {1.00f, 0.55f, 0.80f}, {1.00f, 0.75f, 0.70f},
            {0.70f, 0.70f, 0.80f}, {0.95f, 0.85f, 0.70f},
        };
        private float[] currentColor = PALETTE[0];
        private float[] targetColor  = PALETTE[0];
        private long lastColorChange;
        private static final long COLOR_INTERVAL_MS = 5 * 60 * 1000;
        private static final float COLOR_BLEND_SPEED = 0.012f;
        private final java.util.Random rng = new java.util.Random();

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

            setupShaders();
            generateSphere(0.55f, 48, 24);
            moodStartTime = System.currentTimeMillis();
            lastColorChange = System.currentTimeMillis();
            currentColor = PALETTE[rng.nextInt(PALETTE.length)];
            targetColor = currentColor;
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
            float ratio = (float) width / height;
            Matrix.perspectiveM(projMatrix, 0, 45.0f, ratio, 0.1f, 100.0f);
            Matrix.setLookAtM(viewMatrix, 0, 0, 0, 3.5f, 0, 0, 0, 0, 1, 0);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

            // 旋转动画
            rotationAngle += 1.2f;
            if (rotationAngle > 360f) rotationAngle -= 360f;

            // 颜色渐变
            long now = System.currentTimeMillis();
            if (now - lastColorChange > COLOR_INTERVAL_MS) {
                targetColor = PALETTE[rng.nextInt(PALETTE.length)];
                lastColorChange = now;
            }
            // 平滑插值
            for (int ci = 0; ci < 3; ci++) {
                currentColor[ci] += (targetColor[ci] - currentColor[ci]) * COLOR_BLEND_SPEED;
            }

            // 心情动画
            long elapsed = System.currentTimeMillis() - moodStartTime;
            switch (mood) {
                case EXCITED:
                    pulseScale = 1.0f + 0.15f * (float) Math.sin(elapsed * 0.02);
                    break;
                case HAPPY:
                    pulseScale = 1.0f + 0.08f * (float) Math.sin(elapsed * 0.015);
                    break;
                default:
                    pulseScale = 1.0f + 0.03f * (float) Math.sin(elapsed * 0.005);
            }
            if (elapsed > 3000 && mood != Mood.IDLE) setMood(Mood.IDLE);

            Matrix.setIdentityM(modelMatrix, 0);
            Matrix.rotateM(modelMatrix, 0, rotationAngle, 0, 1, 0);
            Matrix.rotateM(modelMatrix, 0, 15.0f, 1, 0, 0);
            Matrix.scaleM(modelMatrix, 0, pulseScale, pulseScale, pulseScale);

            // 计算 MVP 矩阵
            float[] mvMatrix = new float[16];
            Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0);
            Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvMatrix, 0);

            GLES20.glUseProgram(program);

            // 传递 uniform
            GLES20.glUniformMatrix4fv(uMVPMatrix, 1, false, mvpMatrix, 0);
            GLES20.glUniformMatrix4fv(uMVMatrix, 1, false, mvMatrix, 0);
            // 彩虹色随时间旋转
            float timeSec = (System.currentTimeMillis() % 100000) / 1000.0f;

            // 光源绕球体旋转，增强 3D 立体感
            float lightAngle = timeSec * 0.6f;
            float lx = (float) (3.0 * Math.cos(lightAngle));
            float lz = (float) (3.0 * Math.sin(lightAngle));
            GLES20.glUniform3f(uLightPos, lx, 1.0f, lz);
            GLES20.glUniform3f(uLightColor, 1.0f, 1.0f, 1.0f);
            GLES20.glUniform1f(uTime, timeSec);

            // 绑定顶点
            vertexBuffer.position(0);
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(aPosition);

            normalBuffer.position(0);
            GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 0, normalBuffer);
            GLES20.glEnableVertexAttribArray(aNormal);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);

            GLES20.glDisableVertexAttribArray(aPosition);
            GLES20.glDisableVertexAttribArray(aNormal);
        }

        // ---------- 颜色（12色调色板自动轮换）----------
        private float[] getMoodColor() {
            // 情绪叠加：EXCITED 偏粉，HAPPY 偏亮
            if (mood == Mood.EXCITED) {
                return new float[]{
                    Math.min(1f, currentColor[0] + 0.2f),
                    currentColor[1],
                    Math.min(1f, currentColor[2] + 0.15f)
                };
            }
            if (mood == Mood.HAPPY) {
                return new float[]{
                    currentColor[0],
                    Math.min(1f, currentColor[1] + 0.1f),
                    Math.min(1f, currentColor[2] + 0.1f)
                };
            }
            return currentColor;
        }

        void setMood(Mood m) { this.mood = m; this.moodStartTime = System.currentTimeMillis(); }

        // ---------- 着色器 ----------
        private void setupShaders() {
            String vertSrc =
                "uniform mat4 uMVPMatrix, uMVMatrix;" +
                "attribute vec4 aPosition;" +
                "attribute vec3 aNormal;" +
                "varying vec3 vNormal, vPosition;" +
                "void main() {" +
                "  gl_Position = uMVPMatrix * aPosition;" +
                "  vNormal = mat3(uMVMatrix) * aNormal;" +
                "  vPosition = (uMVMatrix * aPosition).xyz;" +
                "}";

            String fragSrc =
                "precision mediump float;" +
                "uniform vec3 uLightPos, uLightColor;" +
                "uniform float uTime;" +
                "varying vec3 vNormal, vPosition;" +
                "vec3 hue2rgb(float h) {" +
                "  vec3 rgb = clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);" +
                "  return rgb;" +
                "}" +
                "void main() {" +
                // 马赛克：量化屏幕坐标，每 8px 一个色块
                "  vec2 mosaicUV = floor(gl_FragCoord.xy / 8.0) * 8.0;" +
                // 用量化后坐标重算法线方向（产生块状感）
                "  float mosaicAngle = atan(mosaicUV.y - 200.0, mosaicUV.x - 200.0);" +
                "  vec3 normal = normalize(vNormal);" +
                "  vec3 lightDir = normalize(uLightPos - vPosition);" +
                "  vec3 viewDir = normalize(-vPosition);" +
                // 条状七彩：7条离散色带
                "  float hue = mosaicAngle / 6.2832 + 0.5 + uTime * 0.08;" +
                "  hue = fract(hue);" +
                "  float stripe = floor(hue * 18.0) / 18.0;" +
                "  float blend = fract(hue * 18.0);" +
                "  blend = smoothstep(0.0, 0.35, blend) * smoothstep(1.0, 0.65, blend);" +
                "  vec3 rainbow = mix(hue2rgb(stripe + 0.03), hue2rgb(stripe + 0.09), blend);" +
                // 漫反射（半兰伯特）
                "  float diff = max(dot(normal, lightDir), 0.0);" +
                "  float halfLambert = diff * 0.65 + 0.35;" +
                "  vec3 diffuse = halfLambert * uLightColor;" +
                // 环境光
                "  vec3 ambient = 0.12 * uLightColor;" +
                // 高光（Blinn-Phong）
                "  vec3 halfVec = normalize(lightDir + viewDir);" +
                "  float spec = pow(max(dot(normal, halfVec), 0.0), 120.0);" +
                "  vec3 specular = 0.85 * spec * uLightColor;" +
                // 边缘光
                "  float rim = 1.0 - abs(dot(normal, viewDir));" +
                "  rim = pow(rim, 3.5) * 0.25;" +
                "  vec3 rimLight = rim * uLightColor;" +
                // 组合
                "  vec3 result = (ambient * 0.5 + diffuse + specular + rimLight) * rainbow;" +
                "  gl_FragColor = vec4(result, 0.92);" +
                "}";

            int vert = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc);
            int frag = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vert);
            GLES20.glAttachShader(program, frag);
            GLES20.glLinkProgram(program);

            // 获取 attribute/uniform 位置
            aPosition   = GLES20.glGetAttribLocation(program, "aPosition");
            aNormal     = GLES20.glGetAttribLocation(program, "aNormal");
            uMVPMatrix  = GLES20.glGetUniformLocation(program, "uMVPMatrix");
            uMVMatrix   = GLES20.glGetUniformLocation(program, "uMVMatrix");
            uLightPos   = GLES20.glGetUniformLocation(program, "uLightPos");
            uLightColor = GLES20.glGetUniformLocation(program, "uLightColor");
            uObjectColor= GLES20.glGetUniformLocation(program, "uObjectColor");
            uTime      = GLES20.glGetUniformLocation(program, "uTime");
        }

        private int compileShader(int type, String src) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, src);
            GLES20.glCompileShader(shader);
            return shader;
        }

        // ---------- 球体生成 ----------
        private void generateSphere(float radius, int latBands, int lonBands) {
            java.util.ArrayList<Float> verts = new java.util.ArrayList<>();
            java.util.ArrayList<Float> norms = new java.util.ArrayList<>();

            for (int lat = 0; lat <= latBands; lat++) {
                float theta = (float) (lat * Math.PI / latBands);
                float sinTheta = (float) Math.sin(theta);
                float cosTheta = (float) Math.cos(theta);

                for (int lon = 0; lon <= lonBands; lon++) {
                    float phi = (float) (lon * 2 * Math.PI / lonBands);
                    float sinPhi = (float) Math.sin(phi);
                    float cosPhi = (float) Math.cos(phi);

                    float x = cosPhi * sinTheta;
                    float y = cosTheta;
                    float z = sinPhi * sinTheta;

                    verts.add(x * radius); verts.add(y * radius); verts.add(z * radius);
                    norms.add(x); norms.add(y); norms.add(z);
                }
            }

            // 生成三角形索引
            float[] vertices = new float[(latBands) * (lonBands) * 6 * 3];
            float[] normals = new float[vertices.length];
            int idx = 0;
            int cols = lonBands + 1;

            for (int lat = 0; lat < latBands; lat++) {
                for (int lon = 0; lon < lonBands; lon++) {
                    int first = lat * cols + lon;
                    int second = first + cols;

                    int[] tris = {
                        first, second, first + 1,
                        second, second + 1, first + 1
                    };
                    for (int ti : tris) {
                        int base = ti * 3;
                        vertices[idx] = verts.get(base);
                        normals[idx] = norms.get(base); idx++;
                        vertices[idx] = verts.get(base + 1);
                        normals[idx] = norms.get(base + 1); idx++;
                        vertices[idx] = verts.get(base + 2);
                        normals[idx] = norms.get(base + 2); idx++;
                    }
                }
            }

            vertexCount = vertices.length / 3;

            ByteBuffer vb = ByteBuffer.allocateDirect(vertices.length * 4);
            vb.order(ByteOrder.nativeOrder());
            vertexBuffer = vb.asFloatBuffer();
            vertexBuffer.put(vertices);
            vertexBuffer.position(0);

            ByteBuffer nb = ByteBuffer.allocateDirect(normals.length * 4);
            nb.order(ByteOrder.nativeOrder());
            normalBuffer = nb.asFloatBuffer();
            normalBuffer.put(normals);
            normalBuffer.position(0);
        }
    }
}
