/*
 * This file is part of Pufferfish (https://github.com/pufferfish-gg/Pufferfish)
 *
 * Pufferfish is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Pufferfish is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Pufferfish. If not, see <https://www.gnu.org/licenses/>.
 */

package gg.pufferfish.pufferfish.simd;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorSpecies;
import org.slf4j.Logger;

/**
 * Basically, java is annoying, and we have to push this out to its own class.
 */
@Deprecated
public class SIMDChecker {

    @Deprecated
    public static boolean canEnable(Logger logger) {
        try {
            SIMDDetection.testRun = true;

            VectorSpecies<Integer> ISPEC = IntVector.SPECIES_PREFERRED;
            VectorSpecies<Float> FSPEC = FloatVector.SPECIES_PREFERRED;

            logger.info("Max SIMD vector size on this system is {} bits (int)", ISPEC.vectorBitSize());
            logger.info("Max SIMD vector size on this system is " + FSPEC.vectorBitSize() + " bits (float)");

            if (ISPEC.elementSize() < 2 || FSPEC.elementSize() < 2) {
                logger.warn("SIMD is not properly supported on this system!");
                return false;
            }

            return true;
        } catch (NoClassDefFoundError | Exception ignored) {
        } // Basically, we don't do anything. This lets us detect if it's not functional and disable it.
        return false;
    }

}
