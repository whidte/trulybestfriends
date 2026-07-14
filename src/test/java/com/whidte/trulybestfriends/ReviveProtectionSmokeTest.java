package com.whidte.trulybestfriends;

public final class ReviveProtectionSmokeTest {
    private ReviveProtectionSmokeTest() {}

    public static void main(String[] args) {
        long grantedAt = 1000L;
        long expiresAt = grantedAt + 20L;

        require(ReviveProtection.isActive(grantedAt, expiresAt),
                "protection was not active on the grant tick");
        require(ReviveProtection.isActive(expiresAt - 1L, expiresAt),
                "protection ended before all 20 ticks elapsed");
        require(!ReviveProtection.isActive(expiresAt, expiresAt),
                "protection remained active after 20 ticks");

        System.out.println("ReviveProtectionSmokeTest: passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
