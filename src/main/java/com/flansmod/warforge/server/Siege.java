package com.flansmod.warforge.server;

import com.flansmod.warforge.common.DimBlockPos;
import com.flansmod.warforge.common.DimChunkPos;
import com.flansmod.warforge.common.WarForgeConfig;
import com.flansmod.warforge.common.WarForgeMod;
import com.flansmod.warforge.common.blocks.IClaim;
import com.flansmod.warforge.common.blocks.TileEntitySiegeCamp;
import com.flansmod.warforge.common.network.SiegeCampProgressInfo;
import com.flansmod.warforge.server.Faction.PlayerData;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

public class Siege {
    public UUID mAttackingFaction;
    public UUID mDefendingFaction;
    public ArrayList<DimBlockPos> mAttackingSiegeCamps;
    public DimBlockPos mDefendingClaim;

    /**
     * The base progress comes from passive sources and must be recalculated whenever checking progress.
     * Sources for the attackers are:
     * - Additional siege camps
     * Sources of the defenders are:
     * - Adjacent claims with differing support strengths
     * - Defender's flags on the defended claim
     */
    private int mExtraDifficulty = 0;

    /**
     * The attack progress is accumulated over time based on active actions in the area of the siege
     * Sources for the attackers are:
     * - Defender deaths in or around the siege
     * - Elapsed days with no defender logins
     * - Elapsed days (there is a constant pressure from the attacker that will eventually wear down the defenders unless they push back)
     * Sources for the defenders are:
     * - Attacker deaths in or around the siege
     * - Elapsed days with no attacker logins
     */
    private int mAttackProgress = 0;
	
	// This is defined by the chunk we are attacking and what type it is
	public int mBaseDifficulty = 5;
	
	// Attack progress starts at 0 and can be moved to -5 or mAttackSuccessThreshold
	public int GetAttackProgress() { return mAttackProgress; }
	public void setAttackProgress(int progress) { mAttackProgress = progress; }

	public int GetDefenceProgress() { return -mAttackProgress; }
	public int GetAttackSuccessThreshold() { return mBaseDifficulty + mExtraDifficulty; }
	
	public boolean IsCompleted()
	{
		return !hasAbandonedSieges() && GetAttackProgress() >= GetAttackSuccessThreshold() || GetDefenceProgress() >= 5;
	}

	// ensures attackers are within warzone before siege completes
	public boolean hasAbandonedSieges() {
		Faction attacking = WarForgeMod.FACTIONS.getFaction(mAttackingFaction);

		for (DimBlockPos siegeCampPos : mAttackingSiegeCamps) {
			if (siegeCampPos == null) continue;
			// YOU WILL GET INCOMPREHENSIBLE ERRORS IF YOU DO NOT FOLLOW THE BELOW CONVERSION TO REGULAR POS
			TileEntity siegeCamp = WarForgeMod.MC_SERVER.getWorld(siegeCampPos.dim).getTileEntity(siegeCampPos.toRegularPos());
			if (siegeCamp instanceof TileEntitySiegeCamp) {
				int attackerAbandonTimer = ((TileEntitySiegeCamp) siegeCamp).getAttackerAbandonTickTimer();
				if (attackerAbandonTimer > 0) {
					attacking.messageAll(new TextComponentString("Passing of siege delayed due to abandon timer greater than 0 [" + attackerAbandonTimer + " ticks]; ensure abandon timer is 0 to complete siege."));
					return true;
				}
			}
		}

		return false;
	}
	
	public boolean WasSuccessful()
	{
		return GetAttackProgress() >= GetAttackSuccessThreshold();
	}
	
	public Siege()
	{
		mAttackingSiegeCamps = new ArrayList<DimBlockPos>(4);
	}
	
	public Siege(UUID attacker, UUID defender, DimBlockPos defending)
	{
		mAttackingSiegeCamps = new ArrayList<DimBlockPos>(4);
		mAttackingFaction = attacker;
		mDefendingFaction = defender;
		mDefendingClaim = defending;
		
		TileEntity te = WarForgeMod.MC_SERVER.getWorld(defending.dim).getTileEntity(defending.toRegularPos());
		if(te instanceof IClaim)
		{
			mBaseDifficulty = ((IClaim)te).getDefenceStrength();
		}
	}
	
	public SiegeCampProgressInfo GetSiegeInfo()
	{
		Faction attackers = WarForgeMod.FACTIONS.getFaction(mAttackingFaction);
		Faction defenders = WarForgeMod.FACTIONS.getFaction(mDefendingFaction);
		
		if(attackers == null || defenders == null)
		{
			WarForgeMod.LOGGER.error("Invalid factions in siege. Can't display info");
			return null;
		}
		
		SiegeCampProgressInfo info = new SiegeCampProgressInfo();
		info.attackingPos = mAttackingSiegeCamps.get(0);
		info.attackingName = attackers.name;
		info.attackingColour = attackers.colour;
		info.defendingPos = mDefendingClaim;
		info.defendingName = defenders.name;
		info.defendingColour = defenders.colour;
		info.progress = GetAttackProgress();
		info.completionPoint = GetAttackSuccessThreshold();
		
		return info;
	}
	
	public boolean Start() 
	{
		Faction attackers = WarForgeMod.FACTIONS.getFaction(mAttackingFaction);
		Faction defenders = WarForgeMod.FACTIONS.getFaction(mDefendingFaction);
		
		if (attackers == null || defenders == null) {
			WarForgeMod.LOGGER.error("Invalid factions in siege. Cannot start");
			return false;
		}
		
		CalculateBasePower();
		WarForgeMod.INSTANCE.messageAll(new TextComponentString(attackers.name + " started a siege against " + defenders.name), true);
		WarForgeMod.FACTIONS.SendSiegeInfoToNearby(mDefendingClaim.toChunkPos());
		return true;
	}
	
	public void AdvanceDay()
	{
		Faction attackers = WarForgeMod.FACTIONS.getFaction(mAttackingFaction);
		Faction defenders = WarForgeMod.FACTIONS.getFaction(mDefendingFaction);
		
		if(attackers == null || defenders == null)
		{
			WarForgeMod.LOGGER.error("Invalid factions in siege.");
			return;
		}
		
		CalculateBasePower();
		float totalSwing = 0.0f;
		totalSwing += WarForgeConfig.SIEGE_SWING_PER_DAY_ELAPSED_BASE;
		if(!defenders.loggedInToday)
			totalSwing += WarForgeConfig.SIEGE_SWING_PER_DAY_ELAPSED_NO_DEFENDER_LOGINS;
		if(!attackers.loggedInToday)
			totalSwing -= WarForgeConfig.SIEGE_SWING_PER_DAY_ELAPSED_NO_ATTACKER_LOGINS;
		
		
		for(HashMap.Entry<UUID, PlayerData> kvp : defenders.members.entrySet())
		{
			if(kvp.getValue().flagPosition.equals(mDefendingClaim))
			{
				totalSwing -= WarForgeConfig.SIEGE_SWING_PER_DEFENDER_FLAG;
			}
		}
		
		for(HashMap.Entry<UUID, PlayerData> kvp : attackers.members.entrySet())
		{
			if(mAttackingSiegeCamps.contains(kvp.getValue().flagPosition))
			{
				totalSwing += WarForgeConfig.SIEGE_SWING_PER_ATTACKER_FLAG;
			}
		}
		
		mAttackProgress += totalSwing;
		
		if(totalSwing > 0)
		{
			attackers.messageAll(new TextComponentString("Your siege on " + defenders.name + " at " + mDefendingClaim.toFancyString() + " shifted " + totalSwing + " points in your favour. The progress is now at " + GetAttackProgress() + "/" + mBaseDifficulty));
			defenders.messageAll(new TextComponentString("The siege on " + mDefendingClaim.toFancyString() + " by " + attackers.name + " shifted " + totalSwing + " points in their favour. The progress is now at " + GetAttackProgress() + "/" + mBaseDifficulty));
		}
		else if(totalSwing < 0)
		{
			defenders.messageAll(new TextComponentString("The siege on " + mDefendingClaim.toFancyString() + " by " + attackers.name + " shifted " + -totalSwing + " points in your favour. The progress is now at " + GetAttackProgress() + "/" + mBaseDifficulty));
			attackers.messageAll(new TextComponentString("Your siege on " + defenders.name + " at " + mDefendingClaim.toFancyString() + " shifted " + -totalSwing + " points in their favour. The progress is now at " + GetAttackProgress() + "/" + mBaseDifficulty));
		}
		else
		{
			defenders.messageAll(new TextComponentString("The siege on " + mDefendingClaim.toFancyString() + " by " + attackers.name + " did not shift today. The progress is at " + GetAttackProgress() + "/" + mBaseDifficulty));
			attackers.messageAll(new TextComponentString("Your siege on " + defenders.name + " at " + mDefendingClaim.toFancyString() + " did not shift today. The progress is at " + GetAttackProgress() + "/" + mBaseDifficulty));
		}
		
		WarForgeMod.FACTIONS.SendSiegeInfoToNearby(mDefendingClaim.toChunkPos());
	}
	
	public void CalculateBasePower()
	{
		Faction attackers = WarForgeMod.FACTIONS.getFaction(mAttackingFaction);
		Faction defenders = WarForgeMod.FACTIONS.getFaction(mDefendingFaction);
		
		if(attackers == null || defenders == null || WarForgeMod.MC_SERVER == null)
		{
			WarForgeMod.LOGGER.error("Invalid factions in siege.");
			return;
		}
		
		mExtraDifficulty = 0;
		
		// Add a point for each defender flag in place
		for(HashMap.Entry<UUID, PlayerData> kvp : defenders.members.entrySet())
		{
			// 
			if(kvp.getValue().flagPosition.equals(mDefendingClaim))
			{
				mExtraDifficulty += WarForgeConfig.SIEGE_DIFFICULTY_PER_DEFENDER_FLAG;
			}
		}
		
		DimChunkPos defendingChunk = mDefendingClaim.toChunkPos();
		for(EnumFacing direction : EnumFacing.HORIZONTALS)
		{
			DimChunkPos checkChunk = defendingChunk.Offset(direction, 1);
			UUID factionInChunk = WarForgeMod.FACTIONS.getClaim(checkChunk);
			// Sum up all additional attack claims
			if(factionInChunk.equals(mAttackingFaction))
			{
				DimBlockPos claimBlockPos = attackers.getSpecificPosForClaim(checkChunk);
				if(claimBlockPos != null)
				{
					TileEntity te = WarForgeMod.MC_SERVER.getWorld(claimBlockPos.dim).getTileEntity(claimBlockPos.toRegularPos());
					if(te instanceof IClaim)
					{
						mExtraDifficulty += ((IClaim) te).getAttackStrength();
					}
				}
			}
			// Sum up all defending support claims
			if(factionInChunk.equals(mDefendingFaction))
			{
				DimBlockPos claimBlockPos = defenders.getSpecificPosForClaim(checkChunk);
				if(claimBlockPos != null)
				{
					TileEntity te = WarForgeMod.MC_SERVER.getWorld(claimBlockPos.dim).getTileEntity(claimBlockPos.toRegularPos());
					if(te instanceof IClaim)
					{
						mExtraDifficulty -= ((IClaim) te).getSupportStrength();
					}
				}
			}
		}
	}

	// called when siege is ended for any reason and not detected as completed normally
	public void OnCancelled()
	{

		// canceling is only run inside EndSiege, which is only run in TE, so no need for this to do anything
	}

	// called when natural conclusion of siege occurs, not called from TE itself
	public void OnCompleted(boolean successful)
	{
		// for every attacking siege camp attempt to locate it, and if an actual siege camp handle appropriately
		for (DimBlockPos siegeCampPos : mAttackingSiegeCamps) {
			TileEntity siegeCamp = WarForgeMod.MC_SERVER.getWorld(siegeCampPos.dim).getTileEntity(siegeCampPos.toRegularPos());
			if (siegeCamp != null) {
				if (siegeCamp instanceof TileEntitySiegeCamp) {
					if (successful) ((TileEntitySiegeCamp) siegeCamp).cleanupPassedSiege();
					else ((TileEntitySiegeCamp) siegeCamp).cleanupFailedSiege();
				}
			}
		}
	}

    private boolean isPlayerInWarzone(DimBlockPos siegeCampPos, EntityPlayerMP player) {
		// convert siege camp pos to chunk pos and player to chunk pos for clarity
		DimChunkPos siegeCampChunkPos = siegeCampPos.toChunkPos();
		DimChunkPos playerChunkPos = new DimChunkPos(player.dimension, player.getPosition());

        return isPlayerInRadius(siegeCampChunkPos, playerChunkPos);
    }

	public static boolean isPlayerInRadius(DimChunkPos centerChunkPos, DimChunkPos playerChunkPos) {
		return isPlayerInRadius(centerChunkPos, playerChunkPos, 1);
	}

	public static boolean isPlayerInRadius(DimChunkPos centerChunkPos, DimChunkPos playerChunkPos, int radius) {
		if (playerChunkPos.mDim != centerChunkPos.mDim) return false;

		// Check if the player's chunk coordinates are within a 3x3 chunk area
		int minChunkX = centerChunkPos.x - radius;
		int maxChunkX = centerChunkPos.x + radius;
		int minChunkZ = centerChunkPos.z - radius;
		int maxChunkZ = centerChunkPos.z + radius;

		// Check if the player's chunk coordinates are within the 3x3 area
		return (playerChunkPos.x >= minChunkX && playerChunkPos.x <= maxChunkX)
				&& (playerChunkPos.z >= minChunkZ && playerChunkPos.z <= maxChunkZ);
	}

    public void OnPVPKill(EntityPlayerMP killer, EntityPlayerMP killed) {
        Faction attackers = WarForgeMod.FACTIONS.getFaction(mAttackingFaction);
        Faction defenders = WarForgeMod.FACTIONS.getFaction(mDefendingFaction);
        Faction killerFaction = WarForgeMod.FACTIONS.getFactionOfPlayer(killer.getUniqueID());
        Faction killedFaction = WarForgeMod.FACTIONS.getFactionOfPlayer(killed.getUniqueID());

        if (attackers == null || defenders == null || WarForgeMod.MC_SERVER == null) {
            WarForgeMod.LOGGER.error("Invalid factions in siege.");
            return;
        }

        boolean attackValid = false;
        boolean defendValid = false;

		// there may be multiple siege camps per siege, so ensure kill occurred in radius of any
        for (DimBlockPos siegeCamp : mAttackingSiegeCamps) {
            if (isPlayerInWarzone(siegeCamp, killer)) {
                // First case, an attacker killed a defender
                if ( killerFaction == attackers && killedFaction == defenders ) {
                    attackValid = true;
				// Other case, a defender killed an attacker
                } else if ( killerFaction == defenders && killedFaction == attackers ) {
                    defendValid = true;
                }

            }
        }

		if (!attackValid && !defendValid) return; // no more logic needs to be done for invalid kill

		// update progress appropriately; either valid attack, or def by this point, so state of one bool implies the state of the other
		mAttackProgress += attackValid ? WarForgeConfig.SIEGE_SWING_PER_DEFENDER_DEATH : -WarForgeConfig.SIEGE_SWING_PER_ATTACKER_DEATH;
		WarForgeMod.FACTIONS.SendSiegeInfoToNearby(mDefendingClaim.toChunkPos());

		// build notification
		ITextComponent notification = new TextComponentTranslation("warforge.notification.siege_death",
				killed.getName(), WarForgeConfig.SIEGE_SWING_PER_ATTACKER_DEATH,
				GetAttackProgress(), GetAttackSuccessThreshold(), GetDefenceProgress());

		// send notification
		attackers.messageAll(notification);
		defenders.messageAll(notification);
    }

    public void ReadFromNBT(NBTTagCompound tags) {
        mAttackingSiegeCamps.clear();

        // Get the attacker and defender
        mAttackingFaction = tags.getUniqueId("attacker");
        mDefendingFaction = tags.getUniqueId("defender");

        // Get the important locations
        NBTTagList claimList = tags.getTagList("attackLocations", 11); // IntArray (see NBTBase.class)
        if (claimList != null) {
            for (NBTBase base : claimList) {
                NBTTagIntArray claimInfo = (NBTTagIntArray) base;
                DimBlockPos pos = DimBlockPos.readFromNBT(claimInfo);
                mAttackingSiegeCamps.add(pos);
            }
        }

        mDefendingClaim = DimBlockPos.readFromNBT(tags, "defendLocation");
        mAttackProgress = tags.getInteger("progress");
        mBaseDifficulty = tags.getInteger("baseDifficulty");
        mExtraDifficulty = tags.getInteger("extraDifficulty");
    }

    public void WriteToNBT(NBTTagCompound tags) {
        // Set attacker / defender
        tags.setUniqueId("attacker", mAttackingFaction);
        tags.setUniqueId("defender", mDefendingFaction);

        // Set important locations
        NBTTagList claimsList = new NBTTagList();
        for (DimBlockPos pos : mAttackingSiegeCamps) {
            claimsList.appendTag(pos.writeToNBT());
        }

        tags.setTag("attackLocations", claimsList);
        tags.setTag("defendLocation", mDefendingClaim.writeToNBT());
        tags.setInteger("progress", mAttackProgress);
        tags.setInteger("baseDifficulty", mBaseDifficulty);
        tags.setInteger("extraDifficulty", mExtraDifficulty);
    }
}
