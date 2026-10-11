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

package baritone.alto.integration.claims;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoHooks;
import baritone.alto.AltoLog;
import baritone.alto.integration.AltoIntegration;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import xaero.pac.client.api.OpenPACClientAPI;
import xaero.pac.client.claims.api.IClientClaimsManagerAPI;
import xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI;
import xaero.pac.common.parties.party.api.IPartyAPI;

import java.util.UUID;

/**
 * Open Parties and Claims 0.30.3: Baritone never breaks or places blocks in chunks claimed by other players or the
 * server (your own and your party's claims are fine). Uses the claim data OPAC already sends to your client for its
 * map; unknown chunks are treated as unclaimed, the server still has the final say. Only loaded when OPAC is
 * installed.
 */
public final class ClaimsIntegration implements AltoIntegration {

    /** Chunks around the player that are checked (OPAC syncs claims for the area around you). */
    static final int RADIUS = 12;
    private int tick;

    @Override
    public String name() {
        return "Open Parties and Claims";
    }

    @Override
    public boolean enabled() {
        return Baritone.settings().altoClaims.value;
    }

    @Override
    public void init(Alto alto) {
        alto.addTickListener(a -> {
            if (tick++ % 40 == 0) {
                refresh();
            }
        });
    }

    private void refresh() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !Baritone.settings().altoClaims.value) {
            AltoHooks.publishProtection(null);
            return;
        }
        try {
            OpenPACClientAPI api = OpenPACClientAPI.get();
            IClientClaimsManagerAPI claims = api.getClaimsManager();
            IPartyAPI party = api.getClientPartyStorage().getParty();
            UUID me = p.getUUID();
            ResourceLocation dim = p.level().dimension().location();
            ChunkPos center = p.chunkPosition();
            LongOpenHashSet protectedChunks = new LongOpenHashSet();
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    IPlayerChunkClaimAPI claim = claims.get(dim, center.x + dx, center.z + dz);
                    if (claim == null) {
                        continue;
                    }
                    UUID owner = claim.getPlayerId();
                    if (owner.equals(me) || party != null && party.getMemberInfo(owner) != null) {
                        continue;
                    }
                    protectedChunks.add(ChunkPos.asLong(center.x + dx, center.z + dz));
                }
            }
            if (protectedChunks.isEmpty()) {
                AltoHooks.publishProtection(null);
                return;
            }
            LongOpenHashSet snapshot = protectedChunks; // not modified after publishing
            AltoHooks.publishProtection((x, y, z) -> snapshot.contains(ChunkPos.asLong(x >> 4, z >> 4)));
        } catch (Throwable t) {
            AltoLog.debug("claims refresh failed: " + t);
        }
    }
}
