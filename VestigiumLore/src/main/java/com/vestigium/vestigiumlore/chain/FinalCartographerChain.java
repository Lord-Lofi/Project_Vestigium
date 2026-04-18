package com.vestigium.vestigiumlore.chain;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.model.Season;
import com.vestigium.vestigiumlore.VestigiumLore;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * The Final Cartographer — a 40-step hidden quest chain threaded through the world.
 *
 * Each step has optional gates:
 *   - minOmen / maxOmen     : effective omen score window
 *   - requiredSeason        : null = any season
 *   - requiredFragment      : lore fragment that must have been collected
 *   - requiresBossKill      : named boss type string
 *   - bossKillThreshold     : how many kills required (default 1)
 *
 * Completion grants are additive; the chain tracks per-player progress via
 * Player PDC: "fc_step_{chainId}" → current step index (0 = not started).
 */
public class FinalCartographerChain {

    private static final String CHAIN_ID = "final_cartographer";
    private static final NamespacedKey STEP_KEY =
            new NamespacedKey("vestigium", "fc_step_" + CHAIN_ID);
    private static final NamespacedKey COMPLETED_KEY =
            new NamespacedKey("vestigium", "fc_complete_" + CHAIN_ID);

    private final VestigiumLore plugin;
    private final List<ChainStep> steps;

    public FinalCartographerChain(VestigiumLore plugin) {
        this.plugin = plugin;
        this.steps = buildChain();
    }

    public void init() {
        plugin.getLogger().info("[FinalCartographerChain] Initialized — " + steps.size() + " steps.");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public int getPlayerStep(Player player) {
        return player.getPersistentDataContainer()
                .getOrDefault(STEP_KEY, PersistentDataType.INTEGER, 0);
    }

    public boolean isComplete(Player player) {
        return player.getPersistentDataContainer()
                .getOrDefault(COMPLETED_KEY, PersistentDataType.BOOLEAN, false);
    }

    /**
     * Attempts to advance the player past their current step.
     * Returns true if the step was advanced, false if gates block it.
     */
    public boolean tryAdvance(Player player) {
        if (isComplete(player)) return false;

        int idx = getPlayerStep(player);
        if (idx >= steps.size()) {
            markComplete(player);
            return false;
        }

        ChainStep step = steps.get(idx);
        if (!step.gatesPassed(player)) {
            if (step.blockedMessage != null) {
                player.sendMessage("§8" + step.blockedMessage);
            }
            return false;
        }

        // Advance
        player.getPersistentDataContainer()
                .set(STEP_KEY, PersistentDataType.INTEGER, idx + 1);

        if (step.rewardFragment != null) {
            VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), step.rewardFragment);
        }
        if (step.completionMessage != null) {
            player.sendMessage("§6[The Cartographer] §7" + step.completionMessage);
        }

        if (idx + 1 >= steps.size()) {
            markComplete(player);
        }
        return true;
    }

    /**
     * Force-checks whether the player is currently standing at the trigger location
     * for their current step. Used by LoreDeliveryManager on terminal interact.
     */
    public boolean isOnChainStep(Player player, String structureId) {
        int idx = getPlayerStep(player);
        if (idx >= steps.size()) return false;
        ChainStep step = steps.get(idx);
        return structureId.equals(step.triggerStructureId);
    }

    // -------------------------------------------------------------------------
    // Chain definition
    // -------------------------------------------------------------------------

    private List<ChainStep> buildChain() {
        List<ChainStep> chain = new ArrayList<>();

        // ---- ACT I: The Survey (steps 0-9) -----------------------------------
        chain.add(step(0, "cartographer_waystone_1", null, null,
                "You feel the waystone hum with recognition. The survey begins.",
                "§8The waystone is cold and silent.", null, null, 0));

        chain.add(step(1, "cartographer_waystone_2", null, null,
                "Second marker confirmed. The Cartographer was thorough.",
                null, "cartographer_act1_step2", null, 0));

        chain.add(step(2, "cartographer_waystone_3", null, Season.AUTUMN,
                "The third waystone glows faintly gold. Only in the dying of the year.",
                "§8The waystone waits for a particular season.", null, null, 0));

        chain.add(step(3, "cartographer_waystone_4", null, null,
                "A name is carved here that does not match any census you have found.",
                null, "cartographer_act1_step4", null, 0));

        chain.add(step(4, "cartographer_ruin_archive", null, null,
                "The archive's seal breaks open. Dust older than memory.",
                null, null, null, 0));

        chain.add(step(5, "cartographer_sunken_chamber", null, null,
                "Beneath the water, the chamber breathes. You are not the first here.",
                "§8The chamber is sealed. Perhaps a different tide.",
                "cartographer_act1_step6", null, 0)
                .withOmenMin(100));

        chain.add(step(6, "cartographer_mesa_cairn", null, null,
                "The cairn shifts as you approach. Deliberate stones, deliberate hands.",
                null, null, null, 0));

        chain.add(step(7, "cartographer_jungle_marker", null, null,
                "This marker was placed to be hidden, not found. You found it.",
                null, "cartographer_act1_step8", null, 0));

        chain.add(step(8, "cartographer_tundra_shrine", null, Season.WINTER,
                "The shrine burns cold. You understand now that cold and fire are cousins.",
                "§8The tundra shrine slumbers. Another season calls it awake.", null, null, 0));

        chain.add(step(9, "cartographer_convergence_point_1", null, null,
                "ACT I COMPLETE. The survey is done. The Cartographer left this deliberately.",
                null, "cartographer_act1_complete", null, 0)
                .withOmenMin(50));

        // ---- ACT II: The Census (steps 10-19) --------------------------------
        chain.add(step(10, "cartographer_census_vault", null, null,
                "The census names the dead. Forty names. You recognize three.",
                null, "cartographer_act2_step1", null, 0));

        chain.add(step(11, "cartographer_watchtower_west", null, null,
                "The western watchtower still stands. The inscription reads: 'Do not forget us.'",
                null, null, null, 0));

        chain.add(step(12, "cartographer_undersea_fragment", null, null,
                "The pressure changes. The message in the stone was not for fishermen.",
                "§8Something about this place repels you. The omen is too strong.",
                "cartographer_act2_step3", null, 0)
                .withOmenMax(600));

        chain.add(step(13, "cartographer_nether_gate_relic", null, null,
                "The relic predates the gate. Whatever made the portal came after.",
                null, null, null, 0)
                .withRequiredFragment("cartographer_act2_step3"));

        chain.add(step(14, "cartographer_forgotten_library", null, Season.SPRING,
                "The library blooms in spring. Books grow spines of bark here.",
                "§8The library is dormant. It wakes only when the world renews.", null, null, 0));

        chain.add(step(15, "cartographer_sealed_tomb_1", null, null,
                "The tomb was sealed from the inside. This was a choice.",
                null, "cartographer_act2_step6", null, 0));

        chain.add(step(16, "cartographer_sealed_tomb_2", null, null,
                "The second tomb mirrors the first. Same seal. Different name. Same hand.",
                null, null, null, 0)
                .withRequiredFragment("cartographer_act2_step6"));

        chain.add(step(17, "cartographer_obelisk_plain", null, null,
                "The obelisk points nowhere useful. It points everywhere at once.",
                null, "cartographer_act2_step8", null, 0)
                .withOmenMin(200));

        chain.add(step(18, "cartographer_warden_territory", null, null,
                "A warden died here. The sculk grew around its grief.",
                "§8Something guards this place. Deal with it first.",
                null, "warden_named", 1));

        chain.add(step(19, "cartographer_convergence_point_2", null, null,
                "ACT II COMPLETE. The census is closed. The name at the bottom is yours.",
                null, "cartographer_act2_complete", null, 0)
                .withOmenMin(300));

        // ---- ACT III: The Descent (steps 20-29) ------------------------------
        chain.add(step(20, "cartographer_deepslate_archive", null, null,
                "The archive exists outside the mountain. The mountain grew around it.",
                null, "cartographer_act3_step1", null, 0));

        chain.add(step(21, "cartographer_ancient_city_node_1", null, null,
                "The ancient city remembers every step you have taken in it.",
                "§8The city rejects you. The omen is too bright.",
                null, null, 0)
                .withOmenMax(700));

        chain.add(step(22, "cartographer_ancient_city_node_2", null, null,
                "Second node. The resonance is different here. Older.",
                null, "cartographer_act3_step3", null, 0)
                .withRequiredFragment("cartographer_act3_step1"));

        chain.add(step(23, "cartographer_ancient_city_node_3", null, null,
                "The third node completes the triangle. Something geometric is happening.",
                null, null, null, 0)
                .withRequiredFragment("cartographer_act3_step3"));

        chain.add(step(24, "cartographer_sculk_relay", null, null,
                "The relay is alive. It remembers the signal it was built to carry.",
                "§8The relay is dormant. The omen must be louder.", null, null, 0)
                .withOmenMin(400));

        chain.add(step(25, "cartographer_antecedent_altar", null, null,
                "The altar does not receive offerings. It makes them.",
                null, "cartographer_act3_step6", null, 0)
                .withRequiredBoss("ancient_guardian", 1));

        chain.add(step(26, "cartographer_deep_convergence", null, null,
                "The deep convergence point is not a place. It is a memory of a place.",
                null, null, null, 0)
                .withRequiredFragment("cartographer_act3_step6"));

        chain.add(step(27, "cartographer_end_fragment_site", null, null,
                "The End knows this place. It has been looking at it for a long time.",
                "§8This memory belongs to a season of endings.", null, null, 0)
                .withRequiredSeason(Season.AUTUMN));

        chain.add(step(28, "cartographer_boss_gate", null, null,
                "The gate opens for those who have faced the world's anger.",
                "§8The gate waits for someone who has stood against the world-bosses.",
                null, "world_boss", 2));

        chain.add(step(29, "cartographer_convergence_point_3", null, null,
                "ACT III COMPLETE. The descent is finished. What you found at the bottom: yourself.",
                null, "cartographer_act3_complete", null, 0)
                .withOmenMin(500));

        // ---- ACT IV: The Return (steps 30-39) --------------------------------
        chain.add(step(30, "cartographer_surface_resurgence", null, Season.SPRING,
                "The surface has changed while you were below. It remembers you differently now.",
                "§8The world resurges in spring only.", null, null, 0));

        chain.add(step(31, "cartographer_sky_marker", null, null,
                "The sky marker. You had to go deep to understand it was overhead all along.",
                null, "cartographer_act4_step2", null, 0)
                .withOmenMin(600));

        chain.add(step(32, "cartographer_village_of_the_absent", null, null,
                "The village of the absent. Every house has a door that opens inward.",
                null, null, null, 0)
                .withRequiredFragment("cartographer_act4_step2"));

        chain.add(step(33, "cartographer_sea_archive", null, null,
                "The sea archive exists at high tide only. The Cartographer knew the tides.",
                "§8The archive waits beneath the tide.", null, null, 0)
                .withOmenMin(650));

        chain.add(step(34, "cartographer_echo_chamber", null, null,
                "The echo chamber repeats the first word you said underground. You know what it was.",
                null, "cartographer_act4_step5", null, 0)
                .withRequiredFragment("cartographer_act3_complete"));

        chain.add(step(35, "cartographer_warden_memorial", null, null,
                "The warden memorial is not a warning. It is an apology.",
                "§8The memorial requires a deeper understanding of what was lost.",
                null, "warden_named", 3));

        chain.add(step(36, "cartographer_antecedent_vault", null, null,
                "The Antecedent's vault. Their maps stop here. Yours begins.",
                null, "cartographer_act4_step7", null, 0)
                .withRequiredFragment("cartographer_act3_complete")
                .withOmenMin(700));

        chain.add(step(37, "cartographer_final_approach", null, null,
                "The final approach. The path corrects itself toward the center.",
                null, null, null, 0)
                .withOmenMin(750));

        chain.add(step(38, "cartographer_penultimate_chamber", null, null,
                "Penultimate. The word means 'second to last'. You know what comes next.",
                null, "cartographer_penultimate", null, 0)
                .withRequiredFragment("cartographer_act4_step7"));

        chain.add(step(39, "cartographer_terminus", null, null,
                "THE END. The Cartographer's final message: 'You were the survey all along.'",
                "§8The terminus is not ready for you yet.",
                "cartographer_complete", null, 0)
                .withOmenMin(800)
                .withRequiredFragment("cartographer_penultimate"));

        return Collections.unmodifiableList(chain);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void markComplete(Player player) {
        player.getPersistentDataContainer()
                .set(COMPLETED_KEY, PersistentDataType.BOOLEAN, true);
        VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), "cartographer_complete");
        player.sendMessage("§6[The Final Cartographer] §eYou have reached the terminus.");
        plugin.getLogger().info("Player " + player.getName() + " completed the Final Cartographer chain.");
    }

    private static ChainStep step(int index, String triggerStructureId, String requiredFragment,
                                   Season requiredSeason, String completionMessage,
                                   String blockedMessage, String rewardFragment,
                                   String requiresBossKill, int bossKillThreshold) {
        ChainStep s = new ChainStep();
        s.index = index;
        s.triggerStructureId = triggerStructureId;
        s.requiredFragment = requiredFragment;
        s.requiredSeason = requiredSeason;
        s.completionMessage = completionMessage;
        s.blockedMessage = blockedMessage;
        s.rewardFragment = rewardFragment;
        s.requiresBossKill = requiresBossKill;
        s.bossKillThreshold = bossKillThreshold;
        return s;
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    private static class ChainStep {
        int index;
        String triggerStructureId;
        String requiredFragment;
        Season requiredSeason;
        String completionMessage;
        String blockedMessage;
        String rewardFragment;
        String requiresBossKill;
        int bossKillThreshold;
        int minOmen = 0;
        int maxOmen = Integer.MAX_VALUE;

        ChainStep withOmenMin(int min) { this.minOmen = min; return this; }
        ChainStep withOmenMax(int max) { this.maxOmen = max; return this; }
        ChainStep withRequiredFragment(String frag) { this.requiredFragment = frag; return this; }
        ChainStep withRequiredSeason(Season s) { this.requiredSeason = s; return this; }
        ChainStep withRequiredBoss(String boss, int threshold) {
            this.requiresBossKill = boss;
            this.bossKillThreshold = threshold;
            return this;
        }

        boolean gatesPassed(Player player) {
            int omen = (int) VestigiumLib.getOmenAPI().getEffectiveOmenScore();
            if (omen < minOmen || omen > maxOmen) return false;

            if (requiredSeason != null) {
                Season current = VestigiumLib.getSeasonAPI().getCurrentSeason();
                if (current != requiredSeason) return false;
            }

            if (requiredFragment != null) {
                if (!VestigiumLib.getLoreRegistry().hasFragment(player.getUniqueId(), requiredFragment))
                    return false;
            }

            if (requiresBossKill != null && bossKillThreshold > 0) {
                NamespacedKey killKey = com.vestigium.lib.util.Keys.wardenKillsKey(
                        com.vestigium.lib.VestigiumLib.getInstance(), requiresBossKill);
                int kills = player.getPersistentDataContainer()
                        .getOrDefault(killKey, PersistentDataType.INTEGER, 0);
                if (kills < bossKillThreshold) return false;
            }

            return true;
        }
    }
}
