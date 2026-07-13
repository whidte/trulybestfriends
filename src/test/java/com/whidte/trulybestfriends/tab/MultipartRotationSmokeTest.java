package com.whidte.trulybestfriends.tab;

import org.joml.Quaternionf;

public final class MultipartRotationSmokeTest {
    private MultipartRotationSmokeTest() {}

    public static void main(String[] args) {
        testPitchDirectionMatchesOrdinaryEntities();
        testPitchAxisIsYawInvariant();
        System.out.println("MultipartRotationSmokeTest: passed");
    }

    private static void testPitchDirectionMatchesOrdinaryEntities() {
        float actual = RenderHelper.multipartPitchRadians(1.0f);
        float expected = (float) Math.toRadians(20.0);
        if (Math.abs(actual - expected) > 0.00001f) {
            throw new AssertionError("multipart pitch direction is inverted: " + actual);
        }
    }

    private static void testPitchAxisIsYawInvariant() {
        float pitch = (float) Math.toRadians(35.0);
        Quaternionf frontDelta = pitchDelta(0.0f, pitch);
        Quaternionf sideDelta = pitchDelta((float) (Math.PI / 2.0), pitch);

        float dot = Math.abs(frontDelta.x * sideDelta.x
                + frontDelta.y * sideDelta.y
                + frontDelta.z * sideDelta.z
                + frontDelta.w * sideDelta.w);
        if (dot < 0.9999f) {
            throw new AssertionError("multipart pitch axis changes with horizontal viewing angle: " + dot);
        }
    }

    private static Quaternionf pitchDelta(float yaw, float pitch) {
        Quaternionf withoutPitch = RenderHelper.buildMultipartPose(yaw, 0.0f);
        Quaternionf withPitch = RenderHelper.buildMultipartPose(yaw, pitch);
        return withPitch.mul(new Quaternionf(withoutPitch).invert()).normalize();
    }
}
