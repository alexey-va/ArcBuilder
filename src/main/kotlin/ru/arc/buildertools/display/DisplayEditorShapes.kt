package ru.arc.buildertools.display

import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Slab
import org.bukkit.inventory.ItemStack
import org.joml.Vector3f

/** Native model coordinates, captured once with the content instead of parsed during a gesture. */
internal data class DisplayEditorShape(
    val mesh: DisplayEditorMesh,
    val centreX: Float,
    val centreY: Float,
    val centreZ: Float,
) {
    fun centre() = Vector3f(centreX, centreY, centreZ)
}

internal object DisplayEditorShapes {
    private val blockUnit = DisplayEditorShape(DisplayEditorMesh(1.0, 1.0, 1.0), .5f, .5f, .5f)
    private val bottomSlab = DisplayEditorShape(DisplayEditorMesh(1.0, .5, 1.0), .5f, .25f, .5f)
    private val topSlab = DisplayEditorShape(DisplayEditorMesh(1.0, .5, 1.0), .5f, .75f, .5f)
    private val itemUnit = DisplayEditorShape(DisplayEditorMesh(1.0, 1.0, 1.0), 0f, 0f, 0f)
    private val generatedItem = DisplayEditorShape(DisplayEditorMesh(1.0, 1.0, 1.0 / 16), 0f, 0f, 0f)

    fun block(data: BlockData): DisplayEditorShape = when ((data as? Slab)?.type) {
        Slab.Type.BOTTOM -> bottomSlab
        Slab.Type.TOP -> topSlab
        Slab.Type.DOUBLE, null -> blockUnit
    }

    @Suppress("DEPRECATION")
    fun item(stack: ItemStack): DisplayEditorShape {
        val key = stack.type.key
        if (key.namespace != "minecraft" || key.key !in generatedItemIds) return itemUnit
        if (stack.hasItemMeta()) {
            val meta = stack.itemMeta
            if (meta.hasItemModel() || meta.hasCustomModelData() || meta.hasCustomModelDataComponent()) return itemUnit
        }
        return generatedItem
    }

    /*
     * Minecraft 1.21.11 official client.jar SHA-1 ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd.
     * Only direct minecraft:model item descriptors whose complete model parent chain reaches
     * builtin/generated, without explicit elements or a NONE display transform. Conditional,
     * special and custom models retain the unit fallback. ItemModelGenerator has MIN_Z=7.5,
     * MAX_Z=8.5 (model coordinates /16); ItemDisplayTransform.NONE centres this slab at zero.
     * See docs/DISPLAY_EDITOR.md for provenance and the intentionally bounded model scope.
     */
    private val generatedItemIds = """
        acacia_boat acacia_chest_boat acacia_door acacia_hanging_sign acacia_sapling acacia_sign activator_rail
        allay_spawn_egg allium amethyst_cluster amethyst_shard angler_pottery_sherd apple archer_pottery_sherd
        armadillo_scute armadillo_spawn_egg armor_stand arms_up_pottery_sherd arrow axolotl_bucket
        axolotl_spawn_egg azure_bluet baked_potato bamboo bamboo_chest_raft bamboo_door bamboo_hanging_sign
        bamboo_raft bamboo_sign barrier bat_spawn_egg bee_spawn_egg beef beetroot beetroot_seeds beetroot_soup
        bell birch_boat birch_chest_boat birch_door birch_hanging_sign birch_sapling birch_sign black_candle
        black_dye black_harness black_stained_glass_pane blade_pottery_sherd blaze_powder blaze_rod
        blaze_spawn_egg blue_candle blue_dye blue_egg blue_harness blue_orchid blue_stained_glass_pane
        bogged_spawn_egg bolt_armor_trim_smithing_template bone bone_meal book bordure_indented_banner_pattern
        bowl brain_coral brain_coral_fan bread breeze_rod breeze_spawn_egg brewer_pottery_sherd brewing_stand
        brick brown_candle brown_dye brown_egg brown_harness brown_mushroom brown_stained_glass_pane
        bubble_coral bubble_coral_fan bucket burn_pottery_sherd bush cactus_flower cake camel_husk_spawn_egg
        camel_spawn_egg campfire candle carrot carrot_on_a_stick cat_spawn_egg cauldron cave_spider_spawn_egg
        charcoal cherry_boat cherry_chest_boat cherry_door cherry_hanging_sign cherry_sapling cherry_sign
        chest_minecart chicken chicken_spawn_egg chorus_fruit clay_ball closed_eyeblossom coal
        coast_armor_trim_smithing_template cobweb cocoa_beans cod cod_bucket cod_spawn_egg
        command_block_minecart comparator cooked_beef cooked_chicken cooked_cod cooked_mutton cooked_porkchop
        cooked_rabbit cooked_salmon cookie copper_axe copper_bars copper_chain copper_door
        copper_golem_spawn_egg copper_hoe copper_horse_armor copper_ingot copper_lantern copper_nautilus_armor
        copper_nugget copper_pickaxe copper_shovel copper_sword copper_torch cornflower cow_spawn_egg
        creaking_spawn_egg creeper_banner_pattern creeper_spawn_egg crimson_door crimson_fungus
        crimson_hanging_sign crimson_roots crimson_sign cyan_candle cyan_dye cyan_harness
        cyan_stained_glass_pane dandelion danger_pottery_sherd dark_oak_boat dark_oak_chest_boat dark_oak_door
        dark_oak_hanging_sign dark_oak_sapling dark_oak_sign dead_brain_coral dead_brain_coral_fan
        dead_bubble_coral dead_bubble_coral_fan dead_bush dead_fire_coral dead_fire_coral_fan dead_horn_coral
        dead_horn_coral_fan dead_tube_coral dead_tube_coral_fan debug_stick detector_rail diamond diamond_axe
        diamond_hoe diamond_horse_armor diamond_nautilus_armor diamond_pickaxe diamond_shovel diamond_sword
        disc_fragment_5 dolphin_spawn_egg donkey_spawn_egg dragon_breath dried_kelp drowned_spawn_egg
        dune_armor_trim_smithing_template echo_shard egg elder_guardian_spawn_egg emerald enchanted_book
        enchanted_golden_apple end_crystal ender_dragon_spawn_egg ender_eye ender_pearl enderman_spawn_egg
        endermite_spawn_egg evoker_spawn_egg experience_bottle explorer_pottery_sherd exposed_copper_bars
        exposed_copper_chain exposed_copper_door exposed_copper_lantern eye_armor_trim_smithing_template feather
        fermented_spider_eye fern field_masoned_banner_pattern filled_map fire_charge fire_coral fire_coral_fan
        firefly_bush firework_rocket firework_star flint flint_and_steel flow_armor_trim_smithing_template
        flow_banner_pattern flow_pottery_sherd flower_banner_pattern flower_pot fox_spawn_egg
        friend_pottery_sherd frog_spawn_egg frogspawn furnace_minecart ghast_spawn_egg ghast_tear glass_bottle
        glass_pane glistering_melon_slice globe_banner_pattern glow_berries glow_ink_sac glow_item_frame
        glow_lichen glow_squid_spawn_egg glowstone_dust goat_spawn_egg gold_ingot gold_nugget golden_apple
        golden_axe golden_carrot golden_hoe golden_horse_armor golden_nautilus_armor golden_pickaxe
        golden_shovel golden_sword gray_candle gray_dye gray_harness gray_stained_glass_pane green_candle
        green_dye green_harness green_stained_glass_pane guardian_spawn_egg gunpowder guster_banner_pattern
        guster_pottery_sherd hanging_roots happy_ghast_spawn_egg heart_of_the_sea heart_pottery_sherd
        heartbreak_pottery_sherd hoglin_spawn_egg honey_bottle honeycomb hopper hopper_minecart horn_coral
        horn_coral_fan horse_spawn_egg host_armor_trim_smithing_template howl_pottery_sherd husk_spawn_egg
        ink_sac iron_axe iron_bars iron_chain iron_door iron_golem_spawn_egg iron_hoe iron_horse_armor
        iron_ingot iron_nautilus_armor iron_nugget iron_pickaxe iron_shovel iron_sword item_frame jungle_boat
        jungle_chest_boat jungle_door jungle_hanging_sign jungle_sapling jungle_sign kelp knowledge_book ladder
        lantern lapis_lazuli large_amethyst_bud large_fern lava_bucket lead leaf_litter leather
        leather_horse_armor lever light_blue_candle light_blue_dye light_blue_harness
        light_blue_stained_glass_pane light_gray_candle light_gray_dye light_gray_harness
        light_gray_stained_glass_pane lilac lily_of_the_valley lily_pad lime_candle lime_dye lime_harness
        lime_stained_glass_pane lingering_potion llama_spawn_egg mace magenta_candle magenta_dye magenta_harness
        magenta_stained_glass_pane magma_cream magma_cube_spawn_egg mangrove_boat mangrove_chest_boat
        mangrove_door mangrove_hanging_sign mangrove_propagule mangrove_sign map medium_amethyst_bud melon_seeds
        melon_slice milk_bucket minecart miner_pottery_sherd mojang_banner_pattern mooshroom_spawn_egg
        mourner_pottery_sherd mule_spawn_egg mushroom_stew music_disc_11 music_disc_13 music_disc_5
        music_disc_blocks music_disc_cat music_disc_chirp music_disc_creator music_disc_creator_music_box
        music_disc_far music_disc_lava_chicken music_disc_mall music_disc_mellohi music_disc_otherside
        music_disc_pigstep music_disc_precipice music_disc_relic music_disc_stal music_disc_strad
        music_disc_tears music_disc_wait music_disc_ward mutton name_tag nautilus_shell nautilus_spawn_egg
        nether_brick nether_sprouts nether_star nether_wart netherite_axe netherite_hoe netherite_horse_armor
        netherite_ingot netherite_nautilus_armor netherite_pickaxe netherite_scrap netherite_shovel
        netherite_sword netherite_upgrade_smithing_template oak_boat oak_chest_boat oak_door oak_hanging_sign
        oak_sapling oak_sign ocelot_spawn_egg ominous_bottle ominous_trial_key open_eyeblossom orange_candle
        orange_dye orange_harness orange_stained_glass_pane orange_tulip oxeye_daisy oxidized_copper_bars
        oxidized_copper_chain oxidized_copper_door oxidized_copper_lantern painting pale_hanging_moss
        pale_oak_boat pale_oak_chest_boat pale_oak_door pale_oak_hanging_sign pale_oak_sapling pale_oak_sign
        panda_spawn_egg paper parched_spawn_egg parrot_spawn_egg peony phantom_membrane phantom_spawn_egg
        pig_spawn_egg piglin_banner_pattern piglin_brute_spawn_egg piglin_spawn_egg pillager_spawn_egg
        pink_candle pink_dye pink_harness pink_petals pink_stained_glass_pane pink_tulip pitcher_plant
        pitcher_pod plenty_pottery_sherd pointed_dripstone poisonous_potato polar_bear_spawn_egg
        popped_chorus_fruit poppy porkchop potato potion powder_snow_bucket powered_rail prismarine_crystals
        prismarine_shard prize_pottery_sherd pufferfish pufferfish_bucket pufferfish_spawn_egg pumpkin_pie
        pumpkin_seeds purple_candle purple_dye purple_harness purple_stained_glass_pane quartz rabbit
        rabbit_foot rabbit_hide rabbit_spawn_egg rabbit_stew rail raiser_armor_trim_smithing_template
        ravager_spawn_egg raw_copper raw_gold raw_iron red_candle red_dye red_harness red_mushroom
        red_stained_glass_pane red_tulip redstone redstone_torch repeater resin_brick resin_clump
        rib_armor_trim_smithing_template rose_bush rotten_flesh saddle salmon salmon_bucket salmon_spawn_egg
        scrape_pottery_sherd sculk_vein sea_pickle seagrass sentry_armor_trim_smithing_template
        shaper_armor_trim_smithing_template sheaf_pottery_sherd shears sheep_spawn_egg shelter_pottery_sherd
        short_dry_grass short_grass shulker_shell shulker_spawn_egg silence_armor_trim_smithing_template
        silverfish_spawn_egg skeleton_horse_spawn_egg skeleton_spawn_egg skull_banner_pattern
        skull_pottery_sherd slime_ball slime_spawn_egg small_amethyst_bud sniffer_egg sniffer_spawn_egg
        snort_pottery_sherd snout_armor_trim_smithing_template snow_golem_spawn_egg snowball soul_campfire
        soul_lantern soul_torch spectral_arrow spider_eye spider_spawn_egg spire_armor_trim_smithing_template
        splash_potion spruce_boat spruce_chest_boat spruce_door spruce_hanging_sign spruce_sapling spruce_sign
        squid_spawn_egg stick stone_axe stone_hoe stone_pickaxe stone_shovel stone_sword stray_spawn_egg
        strider_spawn_egg string structure_void sugar sugar_cane sunflower suspicious_stew sweet_berries
        tadpole_bucket tadpole_spawn_egg tall_dry_grass tall_grass tide_armor_trim_smithing_template
        tipped_arrow tnt_minecart torch torchflower torchflower_seeds totem_of_undying trader_llama_spawn_egg
        trial_key tripwire_hook tropical_fish tropical_fish_bucket tropical_fish_spawn_egg tube_coral
        tube_coral_fan turtle_egg turtle_scute turtle_spawn_egg twisting_vines vex_armor_trim_smithing_template
        vex_spawn_egg villager_spawn_egg vindicator_spawn_egg vine wandering_trader_spawn_egg
        ward_armor_trim_smithing_template warden_spawn_egg warped_door warped_fungus warped_fungus_on_a_stick
        warped_hanging_sign warped_roots warped_sign water_bucket waxed_copper_bars waxed_copper_chain
        waxed_copper_door waxed_copper_lantern waxed_exposed_copper_bars waxed_exposed_copper_chain
        waxed_exposed_copper_door waxed_exposed_copper_lantern waxed_oxidized_copper_bars
        waxed_oxidized_copper_chain waxed_oxidized_copper_door waxed_oxidized_copper_lantern
        waxed_weathered_copper_bars waxed_weathered_copper_chain waxed_weathered_copper_door
        waxed_weathered_copper_lantern wayfinder_armor_trim_smithing_template weathered_copper_bars
        weathered_copper_chain weathered_copper_door weathered_copper_lantern weeping_vines wheat wheat_seeds
        white_candle white_dye white_harness white_stained_glass_pane white_tulip
        wild_armor_trim_smithing_template wildflowers wind_charge witch_spawn_egg wither_rose
        wither_skeleton_spawn_egg wither_spawn_egg wolf_spawn_egg wooden_axe wooden_hoe wooden_pickaxe
        wooden_shovel wooden_sword writable_book written_book yellow_candle yellow_dye yellow_harness
        yellow_stained_glass_pane zoglin_spawn_egg zombie_horse_spawn_egg zombie_nautilus_spawn_egg
        zombie_spawn_egg zombie_villager_spawn_egg zombified_piglin_spawn_egg
    """.trimIndent().split(Regex("\\s+")).toSet()
}
