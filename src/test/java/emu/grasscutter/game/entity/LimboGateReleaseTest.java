package emu.grasscutter.game.entity;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.binout.AbilityData;
import emu.grasscutter.game.ability.Ability;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.net.proto.SceneEntityInfoOuterClass.SceneEntityInfo;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;

/**
 * The limbo gate has to be released by the modifier that held it, and by nothing else.
 *
 * <p>Two failure shapes are covered: an unrelated {@code ActionRemoveModifier} must not open the gate
 * just because the tracked table happened to be empty, and a modifier whose name was never resolvable
 * must not be released on that basis either - it has no removal signal at all.
 */
@ExtendWith(ServerResourceFixture.class)
final class LimboGateReleaseTest {

    @Test
    void removingAnUnrelatedModifierKeepsTheActiveGate() throws Exception {
        var entity = new GateEntity();
        track(entity, "Arrary_Split", "HpThreshold", 0.30f);

        entity.onLimboModifierRemoved(ability("Arrary_Split"), "SomeBuffThatJustExpired");

        assertTrue(entity.isLimbo(), "an unrelated removal must not open the gate");
        assertEquals(0.30f, threshold(entity), 1e-6f);
    }

    @Test
    void removingTheNamedModifierReleasesTheGate() throws Exception {
        var entity = new GateEntity();
        track(entity, "Arrary_Split", "HpThreshold", 0.30f);

        entity.onLimboModifierRemoved(ability("Arrary_Split"), "HpThreshold");

        assertFalse(entity.isLimbo());
        assertEquals(0f, threshold(entity), 1e-6f);
    }

    @Test
    void anUntrackedHoldSurvivesEveryTrackedRemoval() throws Exception {
        var entity = new GateEntity();
        // The path that has no ability instance and therefore no name to register under.
        holdUntracked(entity, 1e-6f);
        track(entity, "Arrary_Split", "HpThreshold", 0.30f);

        entity.onLimboModifierRemoved(ability("Arrary_Split"), "HpThreshold");

        assertTrue(entity.isLimbo(), "the untracked hold is the only thing left, and nothing can name it");
        assertEquals(1e-6f, threshold(entity), 1e-9f);
    }

    @Test
    void anUntrackedHoldIsNotOpenedByAnUnrelatedRemoval() throws Exception {
        var entity = new GateEntity();
        holdUntracked(entity, 1e-6f);

        // Nothing was ever tracked by name, so the table is empty while the gate is still held.
        entity.onLimboModifierRemoved(ability("Arrary_Split"), "SomeBuffThatJustExpired");

        assertTrue(entity.isLimbo(), "an empty table is not evidence that the gate may open");
        assertEquals(1e-6f, threshold(entity), 1e-9f);
    }

    @Test
    void overlappingGatesReleaseOneAtATime() throws Exception {
        var entity = new GateEntity();
        track(entity, "Phase_A", "HpThreshold", 0.30f);
        track(entity, "Phase_B", "HpThreshold", 0.80f);

        // The most restrictive hold wins while both are up.
        assertEquals(0.80f, threshold(entity), 1e-6f);

        entity.onLimboModifierRemoved(ability("Phase_B"), "HpThreshold");

        assertTrue(entity.isLimbo(), "one gate is still active");
        assertEquals(0.30f, threshold(entity), 1e-6f);

        entity.onLimboModifierRemoved(ability("Phase_A"), "HpThreshold");

        assertFalse(entity.isLimbo(), "and only now is the entity fully open");
    }

    @Test
    void reRegisteringALowerGateDoesNotLoosenTheExistingOne() throws Exception {
        var entity = new GateEntity();
        track(entity, "Phase_A", "HpThreshold", 0.80f);

        track(entity, "Phase_A", "HpThreshold", 0.30f);

        assertEquals(0.80f, threshold(entity), 1e-6f);

        // One removal still ends that modifier's hold entirely, so the gate opens.
        entity.onLimboModifierRemoved(ability("Phase_A"), "HpThreshold");
        assertFalse(entity.isLimbo());
    }

    @Test
    void theSameModifierNameUnderDifferentAbilitiesIsTrackedSeparately() throws Exception {
        var entity = new GateEntity();
        track(entity, "Phase_A", "HpThreshold", 0.30f);
        track(entity, "Phase_B", "HpThreshold", 0.60f);

        entity.onLimboModifierRemoved(ability("Phase_A"), "HpThreshold");

        assertTrue(entity.isLimbo());
        assertEquals(0.60f, threshold(entity), 1e-6f);
    }

    /**
     * The whole gate has to be released by a named add followed by its named removal.
     *
     * <p>This is the case that regressed: the three-argument entry point delegates to the one
     * argument version first, and that version also took a hold - under a key nothing can ever
     * name - so removing the modifier the client reported as gone left the unnamed hold behind at
     * the Array's literal 0.30, and the boss stayed unkillable after its marked minion died.
     */
    @Test
    void aNamedAddRegistersExactlyOneHoldSoItsReleaseOpensTheGate() throws Exception {
        var entity = new GateEntity();

        entity.onAddAbilityModifier(
                limboModifier(0.30f), ability("Monster_Apparatus_Perpetual"), "FirstSplit_Normal");

        assertTrue(entity.isLimbo());
        assertEquals(0.30f, threshold(entity), 1e-6f);

        assertTrue(
                entity.releaseLimboModifier(
                        ability("Monster_Apparatus_Perpetual"), "FirstSplit_Normal"),
                "the removal has to report that it actually dropped a hold");
        assertFalse(entity.isLimbo(), "the boss must take damage again once the marked minion dies");
    }

    @Test
    void anUnnamedHoldIsReleasedOnlyWhenALimboEndIsReported() throws Exception {
        var entity = new GateEntity();
        entity.onAddAbilityModifier(limboModifier(0.80f)); // no ability, no name available
        assertTrue(entity.isLimbo());

        entity.releaseUntrackedLimbo();

        assertFalse(entity.isLimbo());
    }

    /** The Array ships these as plain constants, so the unnamed path reads the real ratio. */
    private static emu.grasscutter.data.binout.AbilityModifier limboModifier(float ratio) {
        var data = new emu.grasscutter.data.binout.AbilityModifier();
        data.state = emu.grasscutter.data.binout.AbilityModifier.State.Limbo;
        data.properties = new emu.grasscutter.data.binout.AbilityModifier.AbilityModifierProperty();
        data.properties.Actor_HpThresholdRatio =
                new emu.grasscutter.data.common.DynamicFloat(ratio);
        return data;
    }
    private static void track(GameEntity entity, String abilityName, String modifier, float ratio)
            throws Exception {
        entity.trackLimboModifier(ability(abilityName), modifier, ratio);
    }

    private static void holdUntracked(GameEntity entity, float ratio) throws Exception {
        Method hold = GameEntity.class.getDeclaredMethod("holdUntrackedLimbo", float.class);
        hold.setAccessible(true);
        hold.invoke(entity, ratio);
    }

    private static float threshold(GameEntity entity) throws Exception {
        Field field = GameEntity.class.getDeclaredField("limboHpThreshold");
        field.setAccessible(true);
        return field.getFloat(entity);
    }

    /**
     * Only {@code data.abilityName} is read by the gate bookkeeping, so an instance without the rest
     * of an ability's wiring is enough here.
     */
    private static Ability ability(String abilityName) throws Exception {
        var data = (AbilityData) allocate(AbilityData.class);
        data.abilityName = abilityName;
        var instance = (Ability) allocate(Ability.class);
        Field field = Ability.class.getDeclaredField("data");
        field.setAccessible(true);
        field.set(instance, data);
        return instance;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return ((Unsafe) field.get(null)).allocateInstance(type);
    }

    /** A GameEntity with nothing but the fields the gate touches. */
    private static final class GateEntity extends GameEntity {
        private GateEntity() {
            super(null);
        }

        @Override
        public void initAbilities() {}

        @Override
        public int getEntityTypeId() {
            return 0;
        }

        @Override
        public Int2FloatMap getFightProperties() {
            return null;
        }

        @Override
        public Position getPosition() {
            return new Position();
        }

        @Override
        public Position getRotation() {
            return new Position();
        }

        @Override
        public SceneEntityInfo toProto() {
            return SceneEntityInfo.getDefaultInstance();
        }
    }
}
