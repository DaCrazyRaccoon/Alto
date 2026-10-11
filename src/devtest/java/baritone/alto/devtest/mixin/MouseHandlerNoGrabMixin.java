/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.alto.devtest.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DEV-ONLY (test harness, never shipped): the automated test client never captures the mouse cursor, so the
 * person using the computer keeps control of it while tests run. Switch off with -Dalto.devtest.nograb=false.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerNoGrabMixin {

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void altoDevtest$noGrab(CallbackInfo ci) {
        if (!"false".equals(System.getProperty("alto.devtest.nograb"))) {
            ci.cancel();
        }
    }
}
