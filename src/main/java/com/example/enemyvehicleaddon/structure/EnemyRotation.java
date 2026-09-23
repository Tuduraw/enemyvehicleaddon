package com.example.enemyvehicleaddon.structure;

import net.minecraft.util.BlockRotation;

/** Quarter-turn rotation shared by base generation and templates. One clockwise quarter turn (seen from above, +x east, +z south) maps (x, z) to (-z, x) - north (0, -1) becomes east (1, 0) - which is exactly what BlockRotation.CLOCKWISE_90 does to block states. Yaw follows: +90 degrees per turn. */
public final class EnemyRotation {

	private EnemyRotation() {
	}

	public static int[] rotate(int x, int z, int quarterTurns) {
		for (int i = 0; i < Math.floorMod(quarterTurns, 4); i++) {
			int previousX = x;
			x = -z;
			z = previousX;
		}
		return new int[]{x, z};
	}

	public static double[] rotate(double x, double z, int quarterTurns) {
		for (int i = 0; i < Math.floorMod(quarterTurns, 4); i++) {
			double previousX = x;
			x = -z;
			z = previousX;
		}
		return new double[]{x, z};
	}

	public static BlockRotation toBlockRotation(int quarterTurns) {
		return switch (Math.floorMod(quarterTurns, 4)) {
			case 1 -> BlockRotation.CLOCKWISE_90;
			case 2 -> BlockRotation.CLOCKWISE_180;
			case 3 -> BlockRotation.COUNTERCLOCKWISE_90;
			default -> BlockRotation.NONE;
		};
	}
}
