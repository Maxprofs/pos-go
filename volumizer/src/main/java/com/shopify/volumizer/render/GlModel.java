package com.shopify.volumizer.render;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;

import com.shopify.volumizer.render.old.GlHelper;
import com.shopify.volumizer.render.old.GlMesh;
import com.shopify.volumizer.utils.FileUtils;

import java.io.IOException;

/**
 */

public class GlModel {

    private static final String U_VIEW_PROJECTION_MATRIX = "u_ProjectionMatrix";
    private static final String A_POSITION = "a_Position";

    private static float VERTICES[] = {
            0.05f, 0.05f, 0.0f,   // top right
            -0.05f, 0.05f, 0.0f,   // top left
            -0.05f, -0.05f, 0.0f,   // bottom left
            0.05f, -0.05f, 0.0f    // bottom right
    };

    private static short INDICES[] = {1, 2, 0, 3};

    private final GlMesh glMesh;

    private final String vertexShader;
    private final String fragmentShader;
    private int programHandle;

    private float[] modelMatrix = new float[16];
    private float[] viewMatrix = new float[16];
    private float[] projectionMatrix = new float[16];

    GlModel(Context context) throws IOException {
        glMesh = new GlMesh(VERTICES, 3, new float[]{}, 0, INDICES);
        vertexShader = FileUtils.loadStringFromAsset(context, "shaders/gles2.common.vertshader");
        fragmentShader = FileUtils.loadStringFromAsset(context, "shaders/gles2.planeui.fragshader");
    }

    void setUpProgramAndBuffers() {
        glMesh.createVbos();
        programHandle = GlHelper.createProgram(vertexShader, fragmentShader);
    }

    /*
        Need to debug? Eval this before drawMesh()
        Timber.i("modelMatrix:\n%s", MatrixUtils.matrixToString(this.modelMatrix));
        Timber.i("viewMatrix:\n%s",MatrixUtils.matrixToString(this.viewMatrix));
        Timber.i("projectionMatrix:\n%s", MatrixUtils.matrixToString(this.projectionMatrix));
        Timber.i("mvpMatrix:\n%s",MatrixUtils.matrixToString(mvMatrix));
    */
    void draw() {
        GLES20.glUseProgram(programHandle);

        int aPositionHandle = GLES20.glGetAttribLocation(programHandle, A_POSITION);

        int uProjectionMatrixHandle = GLES20.glGetUniformLocation(programHandle, U_VIEW_PROJECTION_MATRIX);

        float[] mvMatrix = new float[16];
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0);
        float[] mvpMatrix = new float[16];
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0);

        GLES20.glUniformMatrix4fv(uProjectionMatrixHandle, 1, false, mvpMatrix, 0);

        glMesh.drawMesh(aPositionHandle);
    }

    public void setModelMatrix(float[] matrix) {
        System.arraycopy(matrix, 0, this.modelMatrix, 0, 16);
    }

    public void setViewMatrix(float[] matrix) {
        System.arraycopy(matrix, 0, this.viewMatrix, 0, 16);
    }

    public void setProjectionMatrix(float[] matrix) {
        System.arraycopy(matrix, 0, this.projectionMatrix, 0, 16);
    }
}
