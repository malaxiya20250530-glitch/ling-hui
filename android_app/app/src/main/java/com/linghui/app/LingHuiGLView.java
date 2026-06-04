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
        private int uMVPMatrix, uMVMatrix, uLightPos, uLightColor, uObjectColor;
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

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

            setupShaders();
            generateSphere(0.45f, 36, 18);
            moodStartTime = System.currentTimeMillis();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
            float ratio = (float) width / height;
            Matrix.perspectiveM(projMatrix, 0, 45.0f, ratio, 0.1f, 100.0f);
            Matrix.setLookAtM(viewMatrix, 0, 0, 0, 4.5f, 0, 0, 0, 0, 1, 0);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

            // 旋转动画
            rotationAngle += 1.2f;
            if (rotationAngle > 360f) rotationAngle -= 360f;

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
            GLES20.glUniform3f(uLightPos, 2.0f, 2.0f, 3.0f);
            GLES20.glUniform3f(uLightColor, 1.0f, 1.0f, 1.0f);

            // 角色颜色随心情变化
            float[] color = getMoodColor();
            GLES20.glUniform3f(uObjectColor, color[0], color[1], color[2]);

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

        // ---------- 心情颜色 ----------
        private float[] getMoodColor() {
            switch (mood) {
                case EXCITED: return new float[]{1.0f, 0.6f, 0.8f};  // 粉色
                case HAPPY:   return new float[]{0.5f, 0.9f, 1.0f};  // 天蓝
                default:      return new float[]{0.7f, 0.7f, 1.0f};  // 淡紫
            }
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
                "uniform vec3 uLightPos, uLightColor, uObjectColor;" +
                "varying vec3 vNormal, vPosition;" +
                "void main() {" +
                "  vec3 normal = normalize(vNormal);" +
                "  vec3 lightDir = normalize(uLightPos - vPosition);" +
                // Phong 漫反射
                "  float diff = max(dot(normal, lightDir), 0.0);" +
                "  vec3 diffuse = diff * uLightColor;" +
                // 环境光
                "  vec3 ambient = 0.15 * uLightColor;" +
                // 高光
                "  vec3 viewDir = normalize(-vPosition);" +
                "  vec3 reflectDir = reflect(-lightDir, normal);" +
                "  float spec = pow(max(dot(viewDir, reflectDir), 0.0), 32.0);" +
                "  vec3 specular = 0.5 * spec * uLightColor;" +
                // 组合
                "  vec3 result = (ambient + diffuse + specular) * uObjectColor;" +
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
