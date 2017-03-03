package com.shopify.volumizer.render;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import com.projecttango.tangosupport.TangoSupport;

public class OpenGlCameraPreview {

    private final String mVss =
            "attribute vec2 a_Position;\n" +
            "attribute vec2 a_TexCoord;\n" +
            "varying vec2 v_TexCoord;\n" +
            "void main() {\n" +
            "  v_TexCoord = a_TexCoord;\n" +
            "  gl_Position = vec4(a_Position.x, a_Position.y, 0.0, 1.0);\n" +
            "}";

    private final String mFss =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES u_Texture;\n" +
            "varying vec2 v_TexCoord;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(u_Texture,v_TexCoord);\n" +
            "}";

    private final float[] textureCoords0 =
            new float[]{1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f};

    private OpenGlMesh mMesh;
    private int[] mTextures = new int[1];
    private int mProgram;

    public OpenGlCameraPreview() {
        mTextures[0] = 0;
        // Vertices positions.
        float[] vtmp = {1.0f, -1.0f, -1.0f, -1.0f, 1.0f, 1.0f, -1.0f, 1.0f};
        // Vertices texture coords.
        float[] ttmp = {1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f};
        // Indices.
        short[] itmp = {0, 2, 1, 3};
        mMesh = new OpenGlMesh(vtmp, 2, ttmp, 2, itmp);
    }

    public void updateTextureUv(int rotation){
        float[] textureCoords =
                TangoSupport.getVideoOverlayUVBasedOnDisplayRotation(textureCoords0, rotation);
        mMesh.setTextureCoords(textureCoords);
    }

    public void setUpProgramAndBuffers() {
        createTextures();
        mMesh.createVbos();
        mProgram = OpenGlHelper.createProgram(mVss, mFss);
    }

    private void createTextures() {
        mTextures = new int[1];
        GLES20.glGenTextures(1, mTextures, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextures[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
    }

    public void drawAsBackground() {
        GLES20.glUseProgram(mProgram);

        int ph = GLES20.glGetAttribLocation(mProgram, "a_Position");
        int tch = GLES20.glGetAttribLocation(mProgram, "a_TexCoord");
        int th = GLES20.glGetUniformLocation(mProgram, "u_Texture");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextures[0]);
        GLES20.glUniform1i(th, 0);

        mMesh.drawMesh(ph, tch);
    }

    public int getTextureId() {
        return mTextures[0];
    }
}