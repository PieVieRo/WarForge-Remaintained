package com.flansmod.warforge.common.world;

import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.feature.WorldGenerator;

public class WorldGenClayPool extends WorldGenerator
{
    private final Block denseForm, indicator, liquidForm;

    public WorldGenClayPool(Block dense, Block indicator, Block liquid)
    {
    	denseForm = dense;
    	this.indicator = indicator;
    	liquidForm = liquid;
    }

    public boolean generate(World worldIn, Random rand, BlockPos position)
    {
//        for (position = position.add(-8, 0, -8); position.getY() > 5 && worldIn.isAirBlock(position); position = position.down())
//        {
//            ;
//        }

        if (position.getY() <= 4)
        {
            return false;
        }
        else
        {
            position = position.down(4);
            boolean[] bitFlags = new boolean[2048];
            int i = rand.nextInt(4) + 4;

            for (int j = 0; j < i; ++j)
            {
                double d0 = rand.nextDouble() * 6.0D + 3.0D;
                double d1 = rand.nextDouble() * 6.0D + 2.0D;
                double d2 = rand.nextDouble() * 6.0D + 3.0D;
                double d3 = rand.nextDouble() * (16.0D - d0 - 2.0D) + 1.0D + d0 / 2.0D;
                double d4 = rand.nextDouble() * (8.0D - d1 - 4.0D) + 2.0D + d1 / 2.0D;
                double d5 = rand.nextDouble() * (16.0D - d2 - 2.0D) + 1.0D + d2 / 2.0D;

                for (int l = 1; l < 15; ++l)
                {
                    for (int i1 = 1; i1 < 15; ++i1)
                    {
                        for (int j1 = 1; j1 < 7; ++j1)
                        {
                            double d6 = ((double)l - d3) / (d0 / 2.0D);
                            double d7 = ((double)j1 - d4) / (d1 / 2.0D);
                            double d8 = ((double)i1 - d5) / (d2 / 2.0D);
                            double d9 = d6 * d6 + d7 * d7 + d8 * d8;

                            if (d9 < 1.0D)
                            {
                                bitFlags[(l * 16 + i1) * 8 + j1] = true;
                            }
                        }
                    }
                }
            }

            for (int k1 = 0; k1 < 16; ++k1)
            {
                for (int l2 = 0; l2 < 16; ++l2)
                {
                    for (int k = 0; k < 8; ++k)
                    {
                        boolean flag = !bitFlags[(k1 * 16 + l2) * 8 + k] && (k1 < 15 && bitFlags[((k1 + 1) * 16 + l2) * 8 + k] || k1 > 0 && bitFlags[((k1 - 1) * 16 + l2) * 8 + k] || l2 < 15 && bitFlags[(k1 * 16 + l2 + 1) * 8 + k] || l2 > 0 && bitFlags[(k1 * 16 + (l2 - 1)) * 8 + k] || k < 7 && bitFlags[(k1 * 16 + l2) * 8 + k + 1] || k > 0 && bitFlags[(k1 * 16 + l2) * 8 + (k - 1)]);

                        if (flag)
                        {
                            Material material = worldIn.getBlockState(position.add(k1, k, l2)).getMaterial();

                            if (k >= 4 && material.isLiquid())
                            {
                                return false;
                            }

                            if (k < 4 && !material.isSolid() && worldIn.getBlockState(position.add(k1, k, l2)).getBlock() != this.liquidForm)
                            {
                                return false;
                            }
                        }
                    }
                }
            }

            for (int l1 = 0; l1 < 16; ++l1)
            {
                for (int i3 = 0; i3 < 16; ++i3)
                {
                    for (int i4 = 0; i4 < 8; ++i4)
                    {
                        if (bitFlags[(l1 * 16 + i3) * 8 + i4])
                        {
                            IBlockState state;
                            if (i4 >= 4) {
                                state = Blocks.AIR.getDefaultState();
                            } else if (i4 == 3) {
                                state = liquidForm.getDefaultState();
                            } else {
                                state = denseForm.getDefaultState();
                            }
                            worldIn.setBlockState(position.add(l1, i4, i3), state, 2);
                        }
                    }
                }
            }

            for (int i2 = 0; i2 < 16; ++i2)
            {
                for (int j3 = 0; j3 < 16; ++j3)
                {
                    for (int j4 = 4; j4 < 8; ++j4)
                    {
                    	
                        if (bitFlags[(i2 * 16 + j3) * 8 + j4])
                        {
                           
                        	BlockPos blockpos = position.add(i2, j4 - 1, j3);
                            if (worldIn.getBlockState(blockpos).getBlock() == Blocks.DIRT && worldIn.getLightFor(EnumSkyBlock.SKY, position.add(i2, j4, j3)) > 0)
                            {
                                Biome biome = worldIn.getBiome(blockpos);

                                worldIn.setBlockState(blockpos, indicator.getDefaultState(), 2);
                            }
                        }
                    }
                }
            }
            
            for (int i2 = 0; i2 < 16; ++i2)
            {
                for (int j3 = 0; j3 < 16; ++j3)
                {
                    for (int j4 = 0; j4 < 4; ++j4)
                    {
                    	
                    	if(rand.nextBoolean() && (i2 - 8) * (i2 - 8) + (j3 - 8) * (j3 - 8) + (j4 - 4) * (j4 - 4) < 6 * 6)
                        {
                    		BlockPos blockpos = position.add(i2, j4 - 1, j3);
                    		if(!worldIn.isAirBlock(blockpos))
                    			worldIn.setBlockState(blockpos, indicator.getDefaultState(), 2);
                        }
                    }
                }
            }

            return true;
        }
    }
}
