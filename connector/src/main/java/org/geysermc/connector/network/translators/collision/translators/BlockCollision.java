/*
 * Copyright (c) 2019-2021 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.connector.network.translators.collision.translators;

import com.nukkitx.math.vector.Vector3d;
import com.nukkitx.math.vector.Vector3i;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.geysermc.connector.network.session.GeyserSession;
import org.geysermc.connector.network.translators.collision.BoundingBox;
import org.geysermc.connector.network.translators.collision.CollisionManager;
import org.geysermc.connector.utils.Axis;

@EqualsAndHashCode
public class BlockCollision {

    @Getter
    protected final BoundingBox[] boundingBoxes;

    @EqualsAndHashCode.Exclude
    protected final ThreadLocal<Vector3i> position;

    /**
     * Store a Vector3d to allow the collision to be offset by a fractional amount
     * This is used only in {@link #checkIntersection(BoundingBox)} and {@link #computeCollisionOffset(BoundingBox, Axis, double)}
     */
    @EqualsAndHashCode.Exclude
    protected final ThreadLocal<Vector3d> positionOffset;

    /**
     * This is used for the step up logic.
     * Usually, the player can only step up a block if they are on the same Y level as its bottom face or higher
     * For snow layers, due to its beforeCorrectPosition method the player can be slightly below (0.125 blocks) and
     * still need to step up
     * This used to be 0 but for now this has been set to 1 as it fixes bed collision
     * I didn't just set it for beds because other collision may also be slightly raised off the ground.
     * If this causes any problems, change this back to 0 and add an exception for beds.
     */
    protected double pushUpTolerance = 1;

    /**
     * This is used to control the maximum distance a face of a bounding box can push the player away
     */
    protected double pushAwayTolerance = CollisionManager.COLLISION_TOLERANCE * 1.1;

    protected BlockCollision(BoundingBox[] boxes) {
        this.boundingBoxes = boxes;
        this.position = new ThreadLocal<>();
        this.positionOffset = new ThreadLocal<>();
    }

    public void setPosition(Vector3i newPosition) {
        this.position.set(newPosition);
    }

    public void setPositionOffset(Vector3d newOffset) {
        this.positionOffset.set(newOffset);
    }

    public void reset() {
        this.position.set(null);
        this.positionOffset.set(null);
    }

    /**
     * Overridden in classes like SnowCollision and GrassPathCollision when correction code needs to be run before the
     * main correction
     */
    public void beforeCorrectPosition(BoundingBox playerCollision) {}

    /**
     * Returns false if the movement is invalid, and in this case it shouldn't be sent to the server and should be
     * cancelled
     * While the Java server should do this, it could result in false flags by anticheat
     * This functionality is currently only used in 6 or 7 layer snow
     */
    public boolean correctPosition(GeyserSession session, BoundingBox playerCollision) {
        Vector3i blockPos = this.position.get();
        int x = blockPos.getX();
        int y = blockPos.getY();
        int z = blockPos.getZ();

        double playerMinY = playerCollision.getMiddleY() - (playerCollision.getSizeY() / 2);
        for (BoundingBox b : this.boundingBoxes) {
            double boxMinY = (b.getMiddleY() + y) - (b.getSizeY() / 2);
            double boxMaxY = (b.getMiddleY() + y) + (b.getSizeY() / 2);
            if (b.checkIntersection(x, y, z, playerCollision) && (playerMinY + pushUpTolerance) >= boxMinY) {
                // Max steppable distance in Minecraft as far as we know is 0.5625 blocks (for beds)
                if (boxMaxY - playerMinY <= 0.5625) {
                    playerCollision.translate(0, boxMaxY - playerMinY, 0);
                    // Update player Y for next collision box
                    playerMinY = playerCollision.getMiddleY() - (playerCollision.getSizeY() / 2);
                }
           }

            // Make player collision slightly bigger to pick up on blocks that could cause problems with Passable
            playerCollision.setSizeX(playerCollision.getSizeX() + CollisionManager.COLLISION_TOLERANCE * 2);
            playerCollision.setSizeZ(playerCollision.getSizeZ() + CollisionManager.COLLISION_TOLERANCE * 2);

            // If the player still intersects the block, then push them out
            // This fixes NoCheatPlus's Passable check
            // This check doesn't allow players right up against the block, so they must be pushed slightly away
            if (b.checkIntersection(x, y, z, playerCollision)) {
                Vector3d relativePlayerPosition = Vector3d.from(playerCollision.getMiddleX() - x,
                        playerCollision.getMiddleY() - y,
                        playerCollision.getMiddleZ() - z);

                // The ULP should give an upper bound on the floating point error
                double xULP = Math.ulp((float) Math.max(Math.abs(playerCollision.getMiddleX()) + playerCollision.getSizeX() / 2.0, Math.abs(x) + 1));
                double zULP = Math.ulp((float) Math.max(Math.abs(playerCollision.getMiddleZ()) + playerCollision.getSizeZ() / 2.0, Math.abs(z) + 1));

                double xPushAwayTolerance = Math.max(pushAwayTolerance, xULP);
                double zPushAwayTolerance = Math.max(pushAwayTolerance, zULP);

                double northFaceZPos = b.getMiddleZ() - (b.getSizeZ() / 2);
                double translateDistance = northFaceZPos - relativePlayerPosition.getZ() - (playerCollision.getSizeZ() / 2);
                if (Math.abs(translateDistance) < zPushAwayTolerance) {
                    playerCollision.translate(0, 0, translateDistance);
                }

                double southFaceZPos = b.getMiddleZ() + (b.getSizeZ() / 2);
                translateDistance = southFaceZPos - relativePlayerPosition.getZ() + (playerCollision.getSizeZ() / 2);
                if (Math.abs(translateDistance) < zPushAwayTolerance) {
                    playerCollision.translate(0, 0, translateDistance);
                }

                double eastFaceXPos = b.getMiddleX() + (b.getSizeX() / 2);
                translateDistance = eastFaceXPos - relativePlayerPosition.getX() + (playerCollision.getSizeX() / 2);
                if (Math.abs(translateDistance) < xPushAwayTolerance) {
                    playerCollision.translate(translateDistance, 0, 0);
                }

                double westFaceXPos = b.getMiddleX() - (b.getSizeX() / 2);
                translateDistance = westFaceXPos - relativePlayerPosition.getX() - (playerCollision.getSizeX() / 2);
                if (Math.abs(translateDistance) < xPushAwayTolerance) {
                    playerCollision.translate(translateDistance, 0, 0);
                }

                double bottomFaceYPos = b.getMiddleY() - (b.getSizeY() / 2);
                translateDistance = bottomFaceYPos - relativePlayerPosition.getY() - (playerCollision.getSizeY() / 2);
                if (Math.abs(translateDistance) < pushAwayTolerance) {
                    playerCollision.translate(0, translateDistance, 0);
                }
            }

            // Set the collision size back to normal
            playerCollision.setSizeX(0.6);
            playerCollision.setSizeZ(0.6);
        }

        return true;
    }

    private Vector3d getFullPos() {
        Vector3i blockPos = this.position.get();
        Vector3d blockOffset = this.positionOffset.get();
        if (blockOffset != null && blockOffset != Vector3d.ZERO) {
            return blockOffset.add(blockPos.getX(), blockPos.getY(), blockPos.getZ());
        }
        return blockPos.toDouble();
    }

    public boolean checkIntersection(BoundingBox playerCollision) {
        Vector3d blockPos = getFullPos();
        for (BoundingBox b : boundingBoxes) {
            if (b.checkIntersection(blockPos, playerCollision)) {
                return true;
            }
        }
        return false;
    }

    public double computeCollisionOffset(BoundingBox boundingBox, Axis axis, double offset) {
        Vector3d blockPos = getFullPos();
        for (BoundingBox b : boundingBoxes) {
            offset = b.getMaxOffset(blockPos, boundingBox, axis, offset);
            if (Math.abs(offset) < CollisionManager.COLLISION_TOLERANCE) {
                return 0;
            }
        }
        return offset;
    }
}