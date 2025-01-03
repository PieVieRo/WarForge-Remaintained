package com.flansmod.warforge.server;

import java.awt.Color;
import java.util.*;

import com.flansmod.warforge.api.ObjectIntPair;
import com.flansmod.warforge.common.DimBlockPos;
import com.flansmod.warforge.common.DimChunkPos;
import com.flansmod.warforge.common.WarForgeConfig;
import com.flansmod.warforge.common.WarForgeMod;
import com.flansmod.warforge.common.blocks.IClaim;
import com.flansmod.warforge.common.blocks.TileEntityCitadel;
import com.flansmod.warforge.common.blocks.TileEntityClaim;
import com.flansmod.warforge.common.blocks.TileEntitySiegeCamp;
import com.flansmod.warforge.common.network.PacketSiegeCampProgressUpdate;
import com.flansmod.warforge.common.network.SiegeCampProgressInfo;
import com.flansmod.warforge.server.Faction.PlayerData;
import com.flansmod.warforge.server.Faction.Role;
import com.mojang.authlib.GameProfile;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

public class FactionStorage {
    private HashMap<UUID, Faction> mFactions = new HashMap<UUID, Faction>();
    // This map contains every single claim, including siege camps.
    // So if you take one of these and try to look it up in the faction, check their active sieges too
    private HashMap<DimChunkPos, UUID> mClaims = new HashMap<DimChunkPos, UUID>();
    
    // This is all the currently active sieges, keyed by the defending position
    private HashMap<DimChunkPos, Siege> sieges = new HashMap<DimChunkPos, Siege>();

	//This is all chunks that are under the "Grace" period
	public HashMap<DimChunkPos, ObjectIntPair<UUID>> conqueredChunks = new HashMap<>();
    
    // SafeZone and WarZone
    public static UUID SAFE_ZONE_ID = Faction.createUUID("safezone");
    public static UUID WAR_ZONE_ID = Faction.createUUID("warzone");
    public static Faction SAFE_ZONE = null;
    public static Faction WAR_ZONE = null;
    public static boolean IsNeutralZone(UUID factionID) { return factionID.equals(SAFE_ZONE_ID) || factionID.equals(WAR_ZONE_ID); }
    
    public FactionStorage()
    {
		InitNeutralZones();
    }
    
    private void InitNeutralZones()
    {
		SAFE_ZONE = new Faction();
		SAFE_ZONE.citadelPos = new DimBlockPos(0,0,0,0); // Overworld origin
		SAFE_ZONE.colour = 0x00ff00;
		SAFE_ZONE.name = "SafeZone";
		SAFE_ZONE.uuid = SAFE_ZONE_ID;
		mFactions.put(SAFE_ZONE_ID, SAFE_ZONE);

		WAR_ZONE = new Faction();
		WAR_ZONE.citadelPos = new DimBlockPos(0,0,0,0); // Overworld origin
		WAR_ZONE.colour = 0xff0000;
		WAR_ZONE.name = "WarZone";
		WAR_ZONE.uuid = WAR_ZONE_ID;
		mFactions.put(WAR_ZONE_ID, WAR_ZONE);
		// Note: We specifically do not put this data in the leaderboard
    }
    
    public boolean IsPlayerInFaction(UUID playerID, UUID factionID)
    {
		if(mFactions.containsKey(factionID))
			return mFactions.get(factionID).isPlayerInFaction(playerID);
		return false;
    }

	public boolean isPlayerDefending(UUID playerID){
		Faction faction = getFactionOfPlayer(playerID);
		List<Siege> Sieges = new ArrayList<>(sieges.values());
		for(Siege siege : Sieges){
			if(siege.mDefendingFaction == faction.uuid){
				return true;
			}
		}

		return false;
	}

	public long getPlayerCooldown(UUID playerID){
		return mFactions.get(playerID).members.get(playerID).moveFlagCooldown;
	}

	public HashMap<DimChunkPos, Siege> getSieges() { return sieges; }

	public HashMap<DimChunkPos, UUID> getClaims() { return mClaims; }

    public boolean IsPlayerRoleInFaction(UUID playerID, UUID factionID, Faction.Role role)
    {
		if(mFactions.containsKey(factionID))
			return mFactions.get(factionID).isPlayerRoleInFaction(playerID, role);
		return false;
    }
    
    public Faction getFaction(UUID factionID)
    {
		if(factionID.equals(Faction.nullUuid))
			return null;

		if(mFactions.containsKey(factionID))
			return mFactions.get(factionID);

		WarForgeMod.LOGGER.error("Could not find a faction with UUID " + factionID);
		return null;
    }
    
    public Faction getFaction(String name)
    {
		for(HashMap.Entry<UUID, Faction> entry : mFactions.entrySet()) {
			if(entry.getValue().name.equals(name))
				return entry.getValue();
		}
		return null;
    }
    

    public Faction GetFactionWithOpenInviteTo(UUID playerID)
    {
		for(HashMap.Entry<UUID, Faction> entry : mFactions.entrySet()) {
			if(entry.getValue().isInvitingPlayer(playerID))
				return entry.getValue();
		}
		return null;
    }

	public String[] GetFactionNames() {
		String[] names = new String[mFactions.size()];
		int i = 0;
		for(HashMap.Entry<UUID, Faction> entry : mFactions.entrySet()) {
			names[i] = entry.getValue().name;
			i++;
		}
		return names;
	}

	// This is called for any non-citadel claim. Citadels can be factionless, so this makes no sense
	public void onNonCitadelClaimPlaced(IClaim claim, EntityLivingBase placer) {
		onNonCitadelClaimPlaced(claim, getFactionOfPlayer(placer.getUniqueID()));
	}

	public void onNonCitadelClaimPlaced(IClaim claim, Faction faction) {
		if(faction != null) {
			TileEntity tileEntity = claim.getAsTileEntity();
			mClaims.put(claim.getClaimPos().toChunkPos(), faction.uuid);

			faction.messageAll(new TextComponentString("Claimed the chunk [" + claim.getClaimPos().toChunkPos().x + ", " + claim.getClaimPos().toChunkPos().z + "] around " + claim.getClaimPos().toFancyString()));

			claim.onServerSetFaction(faction);
			faction.onClaimPlaced(claim);
		} else
			WarForgeMod.LOGGER.error("Invalid placer placed a claim at " + claim.getClaimPos());
	}

	public UUID getClaim(DimBlockPos pos)
    {
		return getClaim(pos.toChunkPos());
    }
    
    public UUID getClaim(DimChunkPos pos)
    {
		if(mClaims.containsKey(pos))
			return mClaims.get(pos);
		return Faction.nullUuid;
    }
    
    public Faction getFactionOfPlayer(UUID playerID)
    {
		for(HashMap.Entry<UUID, Faction> entry : mFactions.entrySet()) {
			if(entry.getValue().isPlayerInFaction(playerID))
				return entry.getValue();
		}
		return null;
    }
    
    public void Update()
    {
		for(HashMap.Entry<UUID, Faction> entry : mFactions.entrySet()) {
			entry.getValue().Update();
		}
    }

	public void updateConqueredChunks(long msUpdateTime) {
		int msPassed = (int) (msUpdateTime - WarForgeMod.previousUpdateTimestamp); // the difference is likely less than 596h (max time storage of int using ms)

		try {
			// concurrent modification exception can occur when conquered chunks is checked by other methods, such as pre block place
			for (DimChunkPos chunkPosKey : conqueredChunks.keySet()) {
				ObjectIntPair<UUID> chunkEntry = conqueredChunks.get(chunkPosKey);

				if (chunkEntry.getRight() < msPassed) conqueredChunks.remove(chunkPosKey);
				else chunkEntry.setRight(chunkEntry.getRight() - msPassed);
			}
		} catch (Exception e) {
			WarForgeMod.LOGGER.atError().log("Error when updating conquered chunk of type " + e + ", with stacktrace:");
			e.printStackTrace();
		}
	}
    
    public void advanceSiegeDay()
    {
		for(HashMap.Entry<DimChunkPos, Siege> kvp : sieges.entrySet()) {
			kvp.getValue().AdvanceDay();
		}

		CheckForCompleteSieges();

		if(!WarForgeConfig.LEGACY_USES_YIELD_TIMER) {
			for(HashMap.Entry<UUID, Faction> entry : mFactions.entrySet()) {
				entry.getValue().increaseLegacy();
			}
		}
    }
    
    public void advanceYieldDay()
    {
		for(HashMap.Entry<UUID, Faction> entry : mFactions.entrySet()) {
			entry.getValue().awardYields();
		}

		if(WarForgeConfig.LEGACY_USES_YIELD_TIMER) {
			for(HashMap.Entry<UUID, Faction> entry : mFactions.entrySet()) {
				entry.getValue().increaseLegacy();
			}
		}
    }
    
    public void playerDied(EntityPlayerMP playerWhoDied, DamageSource source)
    {
		if(source.getTrueSource() instanceof EntityPlayerMP) {
			EntityPlayerMP killer = (EntityPlayerMP)source.getTrueSource();
			Faction killedFac = getFactionOfPlayer(playerWhoDied.getUniqueID());
			Faction killerFac = getFactionOfPlayer(killer.getUniqueID());

			if(killedFac != null && killerFac != null) {
				for(HashMap.Entry<DimChunkPos, Siege> kvp : sieges.entrySet()) {
					kvp.getValue().OnPVPKill(killer, playerWhoDied);
				}

				CheckForCompleteSieges();
			}

			if(killerFac != null) {
				int numTimesKilled = 0;
				if(killerFac.killCounter.containsKey(playerWhoDied.getUniqueID())) {
					numTimesKilled = killerFac.killCounter.get(playerWhoDied.getUniqueID()) + 1;
					killerFac.killCounter.replace(playerWhoDied.getUniqueID(), numTimesKilled);
				} else {
					numTimesKilled = 1;
					killerFac.killCounter.put(playerWhoDied.getUniqueID(), numTimesKilled);
				}

				if(numTimesKilled <= WarForgeConfig.NOTORIETY_KILL_CAP_PER_PLAYER) {
					if(killerFac != killedFac) {
						((EntityPlayer)source.getTrueSource()).sendMessage(new TextComponentString("Killing " + playerWhoDied.getName() + " earned your faction " + WarForgeConfig.NOTORIETY_PER_PLAYER_KILL + " notoriety"));
						killerFac.notoriety += WarForgeConfig.NOTORIETY_PER_PLAYER_KILL;
					}
				} else {
					((EntityPlayer)source.getTrueSource()).sendMessage(new TextComponentString("Your faction has already killed " + playerWhoDied.getName() + " " + numTimesKilled + " times. You will not become more notorious."));
				}
			}
		}
    }
    
    public void clearNotoriety()
    {
		for(HashMap.Entry<UUID, Faction> entry : mFactions.entrySet()) {
			entry.getValue().notoriety = 0;
		}
    }
    
    public void clearLegacy()
    {
		for(HashMap.Entry<UUID, Faction> entry : mFactions.entrySet()) {
			entry.getValue().legacy = 0;
		}
    }

	public void CheckForCompleteSieges() {
		// Cache in a list so we can remove from the siege HashMap
		ArrayList<DimChunkPos> completedSieges = new ArrayList<DimChunkPos>();
		for(HashMap.Entry<DimChunkPos, Siege> kvp : sieges.entrySet()) {
			if(kvp.getValue().IsCompleted())
				completedSieges.add(kvp.getKey());
		}

		// Now process the results
		for(DimChunkPos chunkPos : completedSieges)
			handleCompletedSiege(chunkPos);
	}

	// cleaner separation between action to be done on completed sieges and the determination of these sieges
	public void handleCompletedSiege(DimChunkPos chunkPos) {
		handleCompletedSiege(chunkPos, true);
	}

	// cleanup is done by failing, passing, or cancelling siege through the TE class. If called without boolean, it is assumed to not be from inside TE
	public void handleCompletedSiege(DimChunkPos chunkPos, boolean doCleanup) {
		Siege siege = sieges.get(chunkPos);

		Faction attackers = getFaction(siege.mAttackingFaction);
		Faction defenders = getFaction(siege.mDefendingFaction);
		if(attackers == null || defenders == null) {
			WarForgeMod.LOGGER.error("Invalid factions in completed siege. Nothing will happen.");
			return;
		}

		DimBlockPos blockPos = defenders.getSpecificPosForClaim(chunkPos);
		boolean successful = siege.WasSuccessful();
		if(successful) {
			if (WarForgeConfig.ATTACKER_CONQUERED_CHUNK_PERIOD > 0) {
				conqueredChunks.put(chunkPos, new ObjectIntPair<>(copyUUID(attackers.uuid), WarForgeConfig.ATTACKER_CONQUERED_CHUNK_PERIOD));
				for (DimBlockPos siegeCampPos : siege.mAttackingSiegeCamps)
					if (siegeCampPos != null)
						conqueredChunks.put(siegeCampPos.toChunkPos(), new ObjectIntPair<>(copyUUID(attackers.uuid), WarForgeConfig.ATTACKER_CONQUERED_CHUNK_PERIOD));
			}

			defenders.onClaimLost(blockPos, true); // drops block if SIEGE_CAPTURE is off (claim is not overriden), or drops nothing if it is on (claim effectively removed and instantly replaced)
			mClaims.remove(blockPos.toChunkPos());
			attackers.messageAll(new TextComponentTranslation("warforge.info.siege_won_attackers", attackers.name, blockPos.toFancyString()));
			defenders.messageAll(new TextComponentTranslation("warforge.info.siege_lost_defenders", defenders.name, blockPos.toFancyString()));
			attackers.notoriety += WarForgeConfig.NOTORIETY_PER_SIEGE_ATTACK_SUCCESS;
			if(WarForgeConfig.SIEGE_CAPTURE) {
				WarForgeMod.MC_SERVER.getWorld(blockPos.dim).setBlockState(blockPos.toRegularPos(), WarForgeMod.CONTENT.basicClaimBlock.getDefaultState());
				TileEntity te = WarForgeMod.MC_SERVER.getWorld(blockPos.dim).getTileEntity(blockPos.toRegularPos());
				onNonCitadelClaimPlaced((IClaim)te, attackers);
			}
		} else {
			if (WarForgeConfig.DEFENDER_CONQUERED_CHUNK_PERIOD > 0) {
				conqueredChunks.put(chunkPos, new ObjectIntPair<>(copyUUID(defenders.uuid), WarForgeConfig.DEFENDER_CONQUERED_CHUNK_PERIOD)); // defenders get won claims defended
				for (DimBlockPos siegeCampPos : siege.mAttackingSiegeCamps)
					if (siegeCampPos != null)
						conqueredChunks.put(siegeCampPos.toChunkPos(), new ObjectIntPair<>(copyUUID(defenders.uuid), WarForgeConfig.DEFENDER_CONQUERED_CHUNK_PERIOD));
			}
			attackers.messageAll(new TextComponentTranslation("warforge.info.siege_lost_attackers", attackers.name, blockPos.toFancyString()));
			defenders.messageAll(new TextComponentTranslation("warforge.info.siege_won_defenders", defenders.name, blockPos.toFancyString()));
			defenders.notoriety += WarForgeConfig.NOTORIETY_PER_SIEGE_DEFEND_SUCCESS;
		}

		if (doCleanup) siege.OnCompleted(successful);

		// Then remove the siege
		sieges.remove(chunkPos);
	}

	// copy UUID's are used in the case that the UUID referred to are nulled at some point, which is not ideal for chunk protection
	public static UUID copyUUID(final UUID source) {
		return new UUID(source.getMostSignificantBits(), source.getLeastSignificantBits());
	}
    
    public boolean RequestCreateFaction(TileEntityCitadel citadel, EntityPlayer player, String factionName, int colour)
    {
		if(citadel == null) {
			player.sendMessage(new TextComponentString("You can't create a faction without a citadel"));
			return false;
		}

		if(factionName == null || factionName.isEmpty()) {
			player.sendMessage(new TextComponentString("You can't create a faction with no name"));
			return false;
		}

		if(factionName.length() > WarForgeConfig.FACTION_NAME_LENGTH_MAX) {
			player.sendMessage(new TextComponentString("Name is too long, must be at most " + WarForgeConfig.FACTION_NAME_LENGTH_MAX + " characters"));
			return false;
		}

		for(int i = 0; i < factionName.length(); i++) {
			char c = factionName.charAt(i);
			if('0' <= c && c <= '9')
				continue;
			if('a' <= c && c <= 'z')
				continue;
			if('A' <= c && c <= 'Z')
				continue;

			player.sendMessage(new TextComponentString("Invalid character [" + c + "] in faction name"));
			return false;
		}

		Faction existingFaction = getFactionOfPlayer(player.getUniqueID());
		if(existingFaction != null) {
			player.sendMessage(new TextComponentString("You are already in a faction"));
			return false;
		}

		UUID proposedID = Faction.createUUID(factionName);
		if(mFactions.containsKey(proposedID)) {
			player.sendMessage(new TextComponentString("A faction with the name " + factionName + " already exists"));
			return false;
		}

		if(colour == 0xffffff) {
			colour = Color.HSBtoRGB(WarForgeMod.rand.nextFloat(), WarForgeMod.rand.nextFloat() * 0.5f + 0.5f, 1.0f);
		}

		// All checks passed, create a faction
		Faction faction = new Faction();
		faction.onlinePlayerCount = 1;
		faction.uuid = proposedID;
		faction.name = factionName;
		faction.citadelPos = new DimBlockPos(citadel);
		faction.claims.put(faction.citadelPos, 0);
		faction.colour = colour;
		faction.notoriety = 0;
		faction.legacy = 0;
		faction.wealth = 0;

		mFactions.put(proposedID, faction);
		citadel.onServerSetFaction(faction);
		mClaims.put(citadel.getClaimPos().toChunkPos(), proposedID);
		WarForgeMod.LEADERBOARD.RegisterFaction(faction);

		WarForgeMod.INSTANCE.messageAll(new TextComponentString(player.getName() + " created the faction " + factionName), true);

		faction.addPlayer(player.getUniqueID());
		faction.setLeader(player.getUniqueID());

		return true;
    }
    
    public boolean RequestRemovePlayerFromFaction(ICommandSender remover, UUID factionID, UUID toRemove)
    {
		Faction faction = getFaction(factionID);
		if(faction == null) {
			remover.sendMessage(new TextComponentString("That faction doesn't exist"));
			return false;
		}

		if(!faction.isPlayerInFaction(toRemove)) {
			remover.sendMessage(new TextComponentString("That player is not in that faction"));
			return false;
		}

		boolean canRemove = WarForgeMod.isOp(remover);
		boolean removingSelf = false;
		if(remover instanceof EntityPlayer) {
			UUID removerID = ((EntityPlayer)remover).getUniqueID();
			if(removerID.equals(toRemove)) // remove self
			{
				canRemove = true;
				removingSelf = true;
			}

			if(faction.isPlayerOutrankingOfficer(removerID, toRemove))
				canRemove = true;
		}

		if(!canRemove) {
			remover.sendMessage(new TextComponentString("You don't have permission to remove that player"));
			return false;
		}

		GameProfile userProfile = WarForgeMod.MC_SERVER.getPlayerProfileCache().getProfileByUUID(toRemove);
		if(userProfile != null) {
			if(removingSelf)
				faction.messageAll(new TextComponentString(userProfile.getName() + " left " + faction.name));
			else
				faction.messageAll(new TextComponentString(userProfile.getName() + " was kicked from " + faction.name));
		} else {
			remover.sendMessage(new TextComponentString("Error: Could not get user profile"));
		}

		faction.removePlayer(toRemove);

		sendAllSiegeInfoToNearby();

		return true;
    }
        
    public boolean RequestInvitePlayerToMyFaction(EntityPlayer factionOfficer, UUID invitee)
    {
		Faction myFaction = getFactionOfPlayer(factionOfficer.getUniqueID());
		if(myFaction != null)
			return RequestInvitePlayerToFaction(factionOfficer, myFaction.uuid, invitee);
		return false;
    }
    
    public boolean RequestInvitePlayerToFaction(ICommandSender factionOfficer, UUID factionID, UUID invitee)
    {
		Faction faction = getFaction(factionID);
		if(faction == null) {
			factionOfficer.sendMessage(new TextComponentString("That faction doesn't exist"));
			return false;
		}

		if(!WarForgeMod.isOp(factionOfficer) && !faction.isPlayerRoleInFaction(WarForgeMod.getUUID(factionOfficer), Faction.Role.OFFICER)) {
			factionOfficer.sendMessage(new TextComponentString("You are not an officer of this faction"));
			return false;
		}

		Faction existingFaction = getFactionOfPlayer(invitee);
		if(existingFaction != null) {
			factionOfficer.sendMessage(new TextComponentString("That player is already in a faction"));
			return false;
		}

		// TODO: Faction player limit - grows with claims?

		faction.InvitePlayer(invitee);
		WarForgeMod.MC_SERVER.getPlayerList().getPlayerByUUID(invitee).sendMessage(new TextComponentString("You have received an invite to " + faction.name + ". Type /f accept to join"));

		return true;
    }
    
    public void RequestAcceptInvite(EntityPlayer player)
    {
		Faction inviter = GetFactionWithOpenInviteTo(player.getUniqueID());
		if(inviter != null) {
			inviter.addPlayer(player.getUniqueID());
		} else
			player.sendMessage(new TextComponentString("You have no open invite to accept"));
    }
    
    public boolean RequestTransferLeadership(EntityPlayer factionLeader, UUID factionID, UUID newLeaderID)
    {
		Faction faction = getFaction(factionID);
		if(faction == null) {
			factionLeader.sendMessage(new TextComponentString("That faction does not exist"));
			return false;
		}

		if(!WarForgeMod.isOp(factionLeader) && !faction.isPlayerRoleInFaction(factionLeader.getUniqueID(), Faction.Role.LEADER)) {
			factionLeader.sendMessage(new TextComponentString("You are not the leader of this faction"));
			return false;
		}

		if(!faction.isPlayerInFaction(newLeaderID)) {
			factionLeader.sendMessage(new TextComponentString("That player is not in your faction"));
			return false;
		}

		// Do the set
		if(!faction.setLeader(newLeaderID)) {
			factionLeader.sendMessage(new TextComponentString("Failed to set leader"));
			return false;
		}

		factionLeader.sendMessage(new TextComponentString("Successfully set leader"));
		return true;
    }

	public boolean requestPromote(EntityPlayer factionLeader, EntityPlayerMP target) {
		Faction faction = getFactionOfPlayer(factionLeader.getUniqueID());
		if(faction == null) {
			factionLeader.sendMessage(new TextComponentString("You are not in a faction"));
			return false;
		}
		if(!faction.isPlayerRoleInFaction(factionLeader.getUniqueID(), Role.LEADER)) {
			factionLeader.sendMessage(new TextComponentString("You are not the leader of this faction"));
			return false;
		}
		if(!faction.isPlayerRoleInFaction(target.getUniqueID(), Role.MEMBER)) {
			factionLeader.sendMessage(new TextComponentString("This player cannot be promoted"));
			return false;
		}

		faction.promote(target.getUniqueID());
		return true;
	}

	public boolean requestDemote(EntityPlayer factionLeader, EntityPlayerMP target) {
		Faction faction = getFactionOfPlayer(factionLeader.getUniqueID());
		if(faction == null) {
			factionLeader.sendMessage(new TextComponentString("You are not in a faction"));
			return false;
		}
		if(!faction.isPlayerRoleInFaction(factionLeader.getUniqueID(), Role.LEADER)) {
			factionLeader.sendMessage(new TextComponentString("You are not the leader of this faction"));
			return false;
		}
		if(!faction.isPlayerRoleInFaction(target.getUniqueID(), Role.OFFICER)) {
			factionLeader.sendMessage(new TextComponentString("This player cannot be demoted"));
			return false;
		}

		faction.demote(target.getUniqueID());
		return true;
	}


	public boolean RequestDisbandFaction(EntityPlayer factionLeader, UUID factionID)
    {
		if(factionID.equals(Faction.nullUuid)) {
			Faction faction = getFactionOfPlayer(factionLeader.getUniqueID());
			if(faction != null)
				factionID = faction.uuid;
		}

		if(!IsPlayerRoleInFaction(factionLeader.getUniqueID(), factionID, Faction.Role.LEADER)) {
			factionLeader.sendMessage(new TextComponentString("You are not the leader of this faction"));
			return false;
		}

		Faction faction = mFactions.get(factionID);
		for(Map.Entry<DimBlockPos, Integer> kvp : faction.claims.entrySet()) {
			mClaims.remove(kvp.getKey().toChunkPos());
		}
		faction.disband();
		mFactions.remove(factionID);
		WarForgeMod.LEADERBOARD.UnregisterFaction(faction);

		return true;
    }
    
    public void FactionDefeated(Faction faction)
    {
		for(Map.Entry<DimBlockPos, Integer> kvp : faction.claims.entrySet()) {
			mClaims.remove(kvp.getKey().toChunkPos());
		}
		faction.disband();
		mFactions.remove(faction.uuid);
		WarForgeMod.LEADERBOARD.UnregisterFaction(faction);
    }

	public boolean IsSiegeInProgress(DimChunkPos chunkPos) {

		for(Map.Entry<DimChunkPos, Siege> kvp : sieges.entrySet()) {
			if(kvp.getKey().equals(chunkPos))
				return true;

			for(DimBlockPos attackerPos : kvp.getValue().mAttackingSiegeCamps) {
				if(attackerPos.toChunkPos().equals(chunkPos))
					return true;
			}
		}
		return false;
	}
    
    public void requestStartSiege(EntityPlayer factionOfficer, DimBlockPos siegeCampPos, EnumFacing direction)
    {
		Faction attacking = getFactionOfPlayer(factionOfficer.getUniqueID());

		// for some reason, server tick is in number of ticks and last siege timestamp is in ms, while siege cooldown is in mins (according to description), though through calculations looks like hours? it should be in ms
		if (WarForgeMod.serverTick - attacking.lastSiegeTimestamp < WarForgeConfig.SIEGE_COOLDOWN_FAIL) {
			factionOfficer.sendMessage(new TextComponentString("Your faction is on cooldown on starting a new siege"));

			long s = WarForgeMod.INSTANCE.getCooldownRemainingSeconds(WarForgeConfig.SIEGE_COOLDOWN_FAIL, attacking.lastSiegeTimestamp % 60);
			int m = WarForgeMod.INSTANCE.getCooldownRemainingMinutes(WarForgeConfig.SIEGE_COOLDOWN_FAIL, attacking.lastSiegeTimestamp % 60);
			int h = WarForgeMod.INSTANCE.getCooldownRemainingHours(WarForgeConfig.SIEGE_COOLDOWN_FAIL, attacking.lastSiegeTimestamp);

			factionOfficer.sendMessage(new TextComponentString(String.format("Cooldown remaining: %dh %dm %ds or %d ticks", h, m, s, s * 20)));
			return;
		}

        if(!attacking.isPlayerRoleInFaction(factionOfficer.getUniqueID(), Faction.Role.OFFICER)) {
			factionOfficer.sendMessage(new TextComponentString("You are not an officer of this faction"));
			return;
		}

		// TODO: Verify there aren't existing alliances

		TileEntitySiegeCamp siegeTE = (TileEntitySiegeCamp) WarForgeMod.proxy.GetTile(siegeCampPos);
		DimChunkPos defendingChunk = siegeCampPos.toChunkPos().Offset(direction, 1);
		UUID defendingFactionID = mClaims.get(defendingChunk);
		Faction defending = getFaction(defendingFactionID);

		if(defending == null) {
			factionOfficer.sendMessage(new TextComponentString("Could not find a target faction at that poisition"));
			return;
		}

		DimBlockPos defendingPos = defending.getSpecificPosForClaim(defendingChunk);

		if(IsSiegeInProgress(defendingPos.toChunkPos())) {
			factionOfficer.sendMessage(new TextComponentString("That position is already being sieged"));
			return;
		}

		if (conqueredChunks.get(defendingPos.toChunkPos()) != null) {
			factionOfficer.sendMessage(new TextComponentTranslation("warforge.info.chunk_is_conquered",
					defending.name, WarForgeMod.formatTime(conqueredChunks.get(defendingPos.toChunkPos()).getRight())));
			return;
		}

		Siege siege = new Siege(attacking.uuid, defendingFactionID, defendingPos);
		siege.mAttackingSiegeCamps.add(siegeCampPos);

		requestPlaceFlag((EntityPlayerMP)factionOfficer, siegeCampPos);
		sieges.put(defendingChunk, siege);
		siegeTE.setSiegeTarget(defendingPos);
		siege.Start();

		attacking.lastSiegeTimestamp = WarForgeMod.serverTick;

	}

	public void EndSiege(DimBlockPos getPos) {
		Siege siege = sieges.get(getPos.toChunkPos());
		if(siege != null) {
			siege.OnCancelled();
			sieges.remove(getPos.toChunkPos());
		}
	}

	public void RequestOpClaim(EntityPlayer op, DimChunkPos pos, UUID factionID) {
		Faction zone = getFaction(factionID);
		if(zone == null) {
			op.sendMessage(new TextComponentString("Could not find that faction"));
			return;
		}

		UUID existingClaim = getClaim(pos);
		if(!existingClaim.equals(Faction.nullUuid)) {
			op.sendMessage(new TextComponentString("There is already a claim here"));
			return;
		}

		// Place a bedrock tile entity at 0,0,0 chunk coords
		// This might look a bit dodge in End. It's only for admin claims though
		DimBlockPos tePos = new DimBlockPos(pos.mDim, pos.getXStart(), 0, pos.getZStart());
		op.world.setBlockState(tePos.toRegularPos(), WarForgeMod.CONTENT.adminClaimBlock.getDefaultState());
		TileEntity te = op.world.getTileEntity(tePos.toRegularPos());
		if(te == null || !(te instanceof IClaim)) {
			op.sendMessage(new TextComponentString("Placing admin claim block failed"));
			return;
		}

		onNonCitadelClaimPlaced((IClaim)te, zone);

		op.sendMessage(new TextComponentString("Claimed " + pos + " for faction " + zone.name));

	}
    
    public void SendSiegeInfoToNearby(DimChunkPos siegePos)
    {
		Siege siege = sieges.get(siegePos);
		if(siege != null) {
			SiegeCampProgressInfo info = siege.GetSiegeInfo();
			if(info != null) {
				PacketSiegeCampProgressUpdate packet = new PacketSiegeCampProgressUpdate();
				packet.info = info;
				WarForgeMod.NETWORK.sendToAllAround(packet, siegePos.x * 16, 128d, siegePos.z * 16, WarForgeConfig.SIEGE_INFO_RADIUS + 128f, siegePos.mDim);
			}
		}
    }

	public void sendAllSiegeInfoToNearby() {
		for(HashMap.Entry<DimChunkPos, Siege> kvp : WarForgeMod.FACTIONS.sieges.entrySet()) {
			kvp.getValue().CalculateBasePower();

			SendSiegeInfoToNearby(kvp.getKey());
		}

	}

	// returns arraylist with nulls for invalid claim directions, with positions in their horizontal ordering
	public int GetAdjacentClaims(UUID excludingFaction, DimBlockPos pos, ArrayList<DimChunkPos> positions) {
		// allows setting in horizontal index order, which is useful in some cases
		if (positions.size() < 4) positions = new ArrayList<>(Arrays.asList(new DimChunkPos[4]));
		int numValidTargets = 0;

		// checks all horizontal directions
		for (EnumFacing facing : EnumFacing.HORIZONTALS) {
			DimChunkPos targetChunkPos = pos.toChunkPos().Offset(facing, 1);
			UUID targetID = getClaim(targetChunkPos);
			if (!IsClaimed(excludingFaction, targetChunkPos)) continue; // also screens for dimension
			int targetY = getFaction(targetID).getSpecificPosForClaim(targetChunkPos).getY();
			if (pos.getY() < targetY - WarForgeConfig.VERTICAL_SIEGE_DIST || pos.getY() > targetY + WarForgeConfig.VERTICAL_SIEGE_DIST) continue;
			positions.set(facing.getHorizontalIndex(), targetChunkPos);
			++numValidTargets;
		}

		return numValidTargets;
	}

	public boolean IsClaimed(UUID excludingFaction, DimChunkPos pos) {
		UUID factionID = getClaim(pos);
		return factionID != null && !factionID.equals(excludingFaction) && !factionID.equals(Faction.nullUuid);
	}

	public static boolean isValidFaction(Faction faction) {
		return faction != null && isValidFaction(faction.uuid);
	}

	public static boolean isValidFaction(UUID factionID) {
		return factionID != null && !factionID.equals(Faction.nullUuid);
	}

	public boolean RequestRemoveClaim(EntityPlayerMP player, DimBlockPos pos) {
		UUID factionID = getClaim(pos);
		Faction faction = getFaction(factionID);
		if(factionID.equals(Faction.nullUuid) || faction == null) {
			player.sendMessage(new TextComponentString("Could not find a claim in that location"));
			return false;
		}

		if(pos.equals(faction.citadelPos)) {
			player.sendMessage(new TextComponentString("Can't remove the citadel without disbanding the faction"));
			return false;
		}

		if(!WarForgeMod.isOp(player) && !faction.isPlayerRoleInFaction(player.getUniqueID(), Role.OFFICER)) {
			player.sendMessage(new TextComponentString("You are not an officer of the faction"));
			return false;
		}

		for(HashMap.Entry<DimChunkPos, Siege> siege : sieges.entrySet()) {
			if(siege.getKey().equals(pos.toChunkPos())) {
				player.sendMessage(new TextComponentString("This claim is currently under siege"));
				return false;
			}

			if(siege.getValue().mAttackingSiegeCamps.contains(pos)) {
				player.sendMessage(new TextComponentString("This siege camp is currently in a siege"));
				return false;
			}
		}

		faction.onClaimLost(pos);
		mClaims.remove(pos.toChunkPos());
		faction.messageAll(new TextComponentString(player.getName() + " unclaimed " + pos.toFancyString()));

		return true;
	}

	public boolean requestPlaceFlag(EntityPlayerMP player, DimBlockPos pos) {
		Faction faction = getFactionOfPlayer(player.getUniqueID());
		if(faction == null) {
			player.sendMessage(new TextComponentString("You are not in a faction"));
			return false;
		}

		sendAllSiegeInfoToNearby();

		return faction.placeFlag(player, pos);
	}

	public boolean RequestSetFactionColour(EntityPlayerMP player, int colour) {
		Faction faction = getFactionOfPlayer(player.getUniqueID());
		if(faction == null) {
			player.sendMessage(new TextComponentString("You are not in a faction"));
			return false;
		}

		if(!faction.isPlayerRoleInFaction(player.getUniqueID(), Role.LEADER)) {
			player.sendMessage(new TextComponentString("You are not the faction leader"));
			return false;
		}

		faction.setColour(colour);
		for(World world : WarForgeMod.MC_SERVER.worlds) {
			for(TileEntity te : world.loadedTileEntityList) {
				if(te instanceof IClaim) {
					if(((IClaim) te).getFaction().equals(faction.uuid)) {
						((IClaim) te).updateColour(colour);
					}
				}
			}
		}

		return true;

	}


	public boolean RequestMoveCitadel(EntityPlayerMP player, DimBlockPos pos) {
		Faction faction = getFactionOfPlayer(player.getUniqueID());
		if(faction == null) {
			player.sendMessage(new TextComponentString("You are not in a faction"));
			return false;
		}

		if(!faction.isPlayerRoleInFaction(player.getUniqueID(), Role.LEADER)) {
			player.sendMessage(new TextComponentString("You are not the faction leader"));
			return false;
		}

		for(HashMap.Entry<DimChunkPos, Siege> kvp : sieges.entrySet()) {
			if(kvp.getValue().mDefendingFaction.equals(faction.uuid)) {
				player.sendMessage(new TextComponentString("There is an ongoing siege against your faction"));
				return false;
			}
		}


		if(faction.citadelMoveCooldown > 0) {
			player.sendMessage(new TextComponentString("You must wait an additional " + faction.citadelMoveCooldown + " days until you can move your citadel"));
			return false;
		}

		// Set new citadel
		WarForgeMod.MC_SERVER.getWorld(pos.dim).setBlockState(pos.toRegularPos(), WarForgeMod.CONTENT.citadelBlock.getDefaultState());
		TileEntityCitadel newCitadel = (TileEntityCitadel)WarForgeMod.MC_SERVER.getWorld(pos.dim).getTileEntity(pos.toRegularPos());

		// Convert old citadel to basic claim
		WarForgeMod.MC_SERVER.getWorld(faction.citadelPos.dim).setBlockState(faction.citadelPos.toRegularPos(), WarForgeMod.CONTENT.basicClaimBlock.getDefaultState());
		TileEntityClaim newClaim = (TileEntityClaim)WarForgeMod.MC_SERVER.getWorld(faction.citadelPos.dim).getTileEntity(faction.citadelPos.toRegularPos());

		// Update pos
		faction.citadelPos = pos;
		newCitadel.onServerSetFaction(faction);
		newClaim.onServerSetFaction(faction);

		WarForgeMod.INSTANCE.messageAll(new TextComponentString(faction.name + " moved their citadel"), true);

		faction.citadelMoveCooldown = WarForgeConfig.CITADEL_MOVE_NUM_DAYS;

		return true;
	}
    
    public void readFromNBT(NBTTagCompound tags) {
		mFactions.clear();
		mClaims.clear();
		sieges.clear();

		InitNeutralZones();

		NBTTagList list = tags.getTagList("factions", 10); // Compound Tag
		for(NBTBase baseTag : list) {
			NBTTagCompound factionTags = ((NBTTagCompound)baseTag);
			UUID uuid = factionTags.getUniqueId("id");
			Faction faction;


			if(uuid.equals(SAFE_ZONE_ID)) {
				faction = SAFE_ZONE;
			} else if(uuid.equals(WAR_ZONE_ID)) {
				faction = WAR_ZONE;
			} else {
				faction = new Faction();
				faction.uuid = uuid;
				mFactions.put(uuid, faction);
				WarForgeMod.LEADERBOARD.RegisterFaction(faction);
			}

			faction.readFromNBT(factionTags);

			// Also populate the DimChunkPos lookup table
			for(DimBlockPos blockPos : faction.claims.keySet()) {
				mClaims.put(blockPos.toChunkPos(), uuid);
			}
		}

		list = tags.getTagList("sieges", 10); // Compound Tag
		for(NBTBase baseTag : list) {
			NBTTagCompound siegeTags = ((NBTTagCompound)baseTag);
			int dim = siegeTags.getInteger("dim");
			int x = siegeTags.getInteger("x");
			int z = siegeTags.getInteger("z");

			Siege siege = new Siege();
			siege.ReadFromNBT(siegeTags);

			sieges.put(new DimChunkPos(dim, x, z), siege);
		}

		readConqueredChunks(tags);
	}

	private void readConqueredChunks(NBTTagCompound tags) {
		conqueredChunks = new HashMap<>();
		NBTTagCompound conqueredChunksDataList = tags.getCompoundTag("conqueredChunks");

		// 11 is type id for int array
		int index = 0;
		while (true) {
			NBTTagList keyValPair = conqueredChunksDataList.getTagList(new StringBuilder("conqueredChunk_").append(index).toString(), 11);
			if (keyValPair.isEmpty()) break; // exit once invalid (empty, since getTagList never returns null) is found, as it is assumed this is the first non-existent/ invalid index
			int[] dimInfo = keyValPair.getIntArrayAt(0);
			DimChunkPos chunkPosKey = new DimChunkPos(dimInfo[0], dimInfo[1], dimInfo[2]);
			UUID factionID = BEIntArrayToUUID(keyValPair.getIntArrayAt(1));

			conqueredChunks.put(chunkPosKey, new ObjectIntPair<>(factionID, keyValPair.getIntArrayAt(2)[0]));
			++index;
		}
	}

	public void WriteToNBT(NBTTagCompound tags) {
		NBTTagList factionList = new NBTTagList();
		for(HashMap.Entry<UUID, Faction> kvp : mFactions.entrySet()) {
			NBTTagCompound factionTags = new NBTTagCompound();
			factionTags.setUniqueId("id", kvp.getKey());
			kvp.getValue().writeToNBT(factionTags);
			factionList.appendTag(factionTags);
		}

		tags.setTag("factions", factionList);

		NBTTagList siegeList = new NBTTagList();
		for(HashMap.Entry<DimChunkPos, Siege> kvp : sieges.entrySet()) {
			NBTTagCompound siegeTags = new NBTTagCompound();
			siegeTags.setInteger("dim", kvp.getKey().mDim);
			siegeTags.setInteger("x", kvp.getKey().x);
			siegeTags.setInteger("z", kvp.getKey().z);
			kvp.getValue().WriteToNBT(siegeTags);
			siegeList.appendTag(siegeTags);
		}

		tags.setTag("sieges", siegeList);

		writeConqueredChunks(tags);
	}

	private void writeConqueredChunks(NBTTagCompound tags) {
		NBTTagCompound conqueredChunksDataList = new NBTTagCompound();
		int index = 0;
		for (DimChunkPos chunkPosKey : conqueredChunks.keySet()) {
			// values in tag list must all be same, so all types are changed to use int arrays
			NBTTagList keyValPair = new NBTTagList();
			keyValPair.appendTag(new NBTTagIntArray(new int[] {chunkPosKey.mDim, chunkPosKey.x, chunkPosKey.z}));
			keyValPair.appendTag(new NBTTagIntArray(UUIDToBEIntArray(conqueredChunks.get(chunkPosKey).getLeft())));
			keyValPair.appendTag(new NBTTagIntArray(new int[] {conqueredChunks.get(chunkPosKey).getRight()}));

			conqueredChunksDataList.setTag(new StringBuilder("conqueredChunk_").append(index).toString(), keyValPair);

			++index;
		}

		tags.setTag("conqueredChunks", conqueredChunksDataList);
	}

	// returns big endian (decreasing sig/ biggest sig first) array
	public static int[] UUIDToBEIntArray(UUID uniqueID) {
		return new int[] {
				(int) (uniqueID.getMostSignificantBits() >>> 32),
				(int) uniqueID.getMostSignificantBits(),
				(int) (uniqueID.getLeastSignificantBits() >>> 32),
				(int) uniqueID.getLeastSignificantBits()
		};
	}

	public static UUID BEIntArrayToUUID(int[] bigEndianArray) {
		// FUCKING SIGN EXTENSION
		return new UUID(
				((bigEndianArray[0] & 0xFFFF_FFFFL) << 32) | (bigEndianArray[1] & 0xFFFF_FFFFL),
				((bigEndianArray[2] & 0xFFFF_FFFFL) << 32) | (bigEndianArray[3] & 0xFFFF_FFFFL)
		);
	}

	public void opResetFlagCooldowns() {
		for(HashMap.Entry<UUID, Faction> kvp : mFactions.entrySet()) {
			for(HashMap.Entry<UUID, PlayerData> pDataKVP : kvp.getValue().members.entrySet()) {
				//pDataKVP.getValue().mHasMovedFlagToday = false;
				pDataKVP.getValue().moveFlagCooldown = 0;
			}
		}
	}
}
