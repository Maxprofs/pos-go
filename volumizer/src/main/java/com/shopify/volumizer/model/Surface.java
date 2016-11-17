package com.shopify.volumizer.model;

import android.support.annotation.NonNull;

import org.rajawali3d.Object3D;
import org.rajawali3d.math.Matrix4;
import org.rajawali3d.math.Plane;
import org.rajawali3d.math.Quaternion;
import org.rajawali3d.math.vector.Vector3;

/**
 */
public class Surface {

    private final Matrix4 planeMat;
    private final Vector3 pos;
    private final Quaternion ori;

    private final Plane plane;

    private final float[] dimensions; // up, down, left, right

    public Surface(float[] planeMat, float[] dimensions) {
        this.planeMat = new Matrix4(planeMat);
        this.pos = this.planeMat.getTranslation();
        this.ori = new Quaternion().fromMatrix(this.planeMat).conjugate();

        this.plane = buildMathPlane();

        this.dimensions = dimensions;
    }

    @NonNull
    private Plane buildMathPlane() {
        // TODO: This stateful stuff scares me. Investigate how to turn to a functional approach.
        Object3D o = new Object3D();
        Vector3 a = new Vector3(), b = new Vector3(), c = new Vector3();

        a.setAll(pos);

        o.setPosition(pos);
        o.setOrientation(ori);
        o.moveRight(1);
        b.setAll(o.getPosition());

        o.setPosition(pos);
        o.setOrientation(ori);
        o.moveUp(1);
        c.setAll(o.getPosition());

        return new org.rajawali3d.math.Plane(a, b, c);
    }

    public float[] getDimensions() {
        return dimensions;
    }

    private Vector3 getPosition() {
        return planeMat.getTranslation();
    }

    private Quaternion getOrientation() {
        return ori;
    }
}
