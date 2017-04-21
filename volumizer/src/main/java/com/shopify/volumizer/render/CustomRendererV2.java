package com.shopify.volumizer.render;

import android.app.Activity;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import com.kanawish.gl.Program;
import com.kanawish.gl.Shader;
import com.kanawish.gl.utils.ModelUtils;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import timber.log.Timber;

/**
 */

public class CustomRendererV2 implements GLSurfaceView.Renderer {
    private static final String U_MV_MATRIX = "u_mvMatrix";
    private static final String U_MVP_MATRIX = "u_mvpMatrix";
    private static final String U_LIGHT_POSITION = "u_lightPosition";

    private static final String A_POSITION = "a_Position";
    private static final String A_NORMAL = "a_Normal";
    private final Activity parentActivity;

    private ModelUtils.Ep02Model cube;
    private float[] uLightPosition = new float[3];

    private float[] modelMatrix = new float[16];
    private float[] viewMatrix = new float[16];
    private float[] projectionMatrix = new float[16];

    private float[] uMvMatrix = new float[16];
    private float[] uMvpMatrix = new float[16];

    private int programHandle;

    private int uMvMatrixHandle;
    private int uMvpMatrixHandle;
    private int uLightPositionHandle;

    private int aPositionHandle;
    private int aNormalHandle;

    private long started;

    CustomRendererV2(Activity parent) {
        parentActivity = parent;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        Timber.i("Ep00Renderer.onSurfaceCreated()");

        // OPENGL CONFIGURATION
        // Set the background clear color of your choice.
        GLES20.glClearColor(0.0f, 0.0f, 1.0f, 1.0f);

        // Use culling to remove back faces.
        GLES20.glEnable(GLES20.GL_CULL_FACE);

        // Enable depth testing
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);


        // OPENGL PROGRAM INIT
        // Load episode 02 shaders from "assets/", compile them, returns shader handlers.
        int[] shaderHandles = Shader.compileShadersEp02(parentActivity);

        // Link the shaders to form a program, binding attributes
        programHandle = Program.linkProgram(shaderHandles, A_POSITION, A_NORMAL);

        uMvMatrixHandle = GLES20.glGetUniformLocation(programHandle, U_MV_MATRIX);
        uMvpMatrixHandle = GLES20.glGetUniformLocation(programHandle, U_MVP_MATRIX);
        uLightPositionHandle = GLES20.glGetUniformLocation(programHandle, U_LIGHT_POSITION);

        aPositionHandle = GLES20.glGetAttribLocation(programHandle, A_POSITION);
        aNormalHandle = GLES20.glGetAttribLocation(programHandle, A_NORMAL);

        GLES20.glUseProgram(programHandle);


        // MODEL INIT - Set up model(s)
        // Our cube model.
        cube = ModelUtils.buildCube(1f);

        // LIGHTING INIT
        uLightPosition = new float[]{0f, 2f, -2f};


        // VIEW MATRIX INIT - This call sets up the viewMatrix (our camera).
        Matrix.setLookAtM(
                viewMatrix, 0,  // result array, offset
                0f, 0f, 1.5f,   // coordinates for our 'eye'
                0f, 0f, -5f,    // center of view
                0f, 1.0f, 0.0f  // 'up' vector
        );
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        Timber.i("Ep00Renderer.onSurfaceChanged(%d, %d)", width, height);

        // We want the viewport to match our screen's geometry.
        GLES20.glViewport(0, 0, width, height);

        final float ratio = (float) width / height;

        // PROJECTION MATRIX - This call sets up the projectionMatrix.
        Matrix.frustumM(
                projectionMatrix, 0,    // target matrix, offset
                -ratio, ratio,  // left, right
                -1.0f, 1.0f,    // bottom, top
                1f, 100f         // near, far
        );

        started = System.currentTimeMillis();
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // Refresh our (parent) fps counter.
//        fpsCounter.log();

        // We clear the screen.
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);


        // MODEL - Pass the vertex information (coordinates, normals) to the Vertex Shader
        GLES20.glVertexAttribPointer(
                aPositionHandle,
                ModelUtils.VALUES_PER_COORD,
                GLES20.GL_FLOAT,
                false,
                0,
                cube.getCoordinates());
        GLES20.glEnableVertexAttribArray(aPositionHandle);

        GLES20.glVertexAttribPointer(
                aNormalHandle,
                ModelUtils.VALUES_PER_NORMAL,
                GLES20.GL_FLOAT,
                false,
                0,
                cube.getNormals());
        GLES20.glEnableVertexAttribArray(aNormalHandle);


        // MODEL - Prepares the Model transformation Matrix, for the given elapsed time.
        animateModel(System.currentTimeMillis() - started);


        // MODEL-VIEW-PROJECTION
        // Multiply view by model matrix. uMvMatrix holds the result.
        Matrix.multiplyMM(uMvMatrix, 0, viewMatrix, 0, modelMatrix, 0);
        // Assign matrix to uniform handle.
        GLES20.glUniformMatrix4fv(uMvMatrixHandle, 1, false, uMvMatrix, 0);

        // Multiply model-view matrix by projection matrix, uMvpMatrix holds the result.
        Matrix.multiplyMM(uMvpMatrix, 0, projectionMatrix, 0, uMvMatrix, 0);
        // Assign matrix to uniform handle.
        GLES20.glUniformMatrix4fv(uMvpMatrixHandle, 1, false, uMvpMatrix, 0);


        // Assign light position to uniform handle.
        GLES20.glUniform3f(uLightPositionHandle, uLightPosition[0], uLightPosition[1], uLightPosition[2]);

        // Draw call
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_INT, cube.getIndices());
    }

    void animateModel(long elapsed) {
        final int msCycle = 14000;
        float angle = (elapsed % msCycle) / (float) msCycle * 360f;
        Matrix.setIdentityM(modelMatrix, 0);                // Initialize
        Matrix.translateM(modelMatrix, 0, 0f, 0f, -2f);     // Move model in front of camera (-Z is in front of us)
        Matrix.rotateM(modelMatrix, 0, angle, 1f, 1f, 0f);    // Rotate model on the X axis.
    }}
