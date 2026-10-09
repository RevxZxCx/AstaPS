package emu.grasscutter.game.ability.actions;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.proto.SceneEntityInfoOuterClass.SceneEntityInfo;
import emu.grasscutter.net.proto.VectorOuterClass.Vector;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Where a summon whose born block points at a global position key actually gets placed, and how a
 * born offset is turned into that key's value.
 *
 * <p>Both used to end at the world origin in the open world: proto3 leaves zero-valued fields off the
 * wire, so the client's vector for a position it never learned is {@code (0,0,0)}.
 */
@ExtendWith(ServerResourceFixture.class)
final class SummonBornPositionTest {

    private static final Vector NO_POSITION = Vector.newBuilder().build();

    // --- resolveSummonPosition -------------------------------------------------------------

    @Test
    void aGlobalPositionKeyIsReadBackFromTheCaster() {
        var caster = new BornEntity(new Position(-2125f, 138f, -4465f));
        caster.getGlobalAbilityPositions().put("SplitPos1", new Position(-2118f, 138f, -4458f));

        var pos =
                ActionSummon.resolveSummonPosition(
                        null, caster, bornAction("ConfigBornByGlobalValue", "SplitPos1"), NO_POSITION);

        assertEquals(-2118f, pos.getX(), 1e-4f);
        assertEquals(-4458f, pos.getZ(), 1e-4f);
    }

    @Test
    void aMissingKeySpawnsAtTheCasterAndSaysSoInsteadOfQuietlyUsingTheOrigin() throws Exception {
        var caster = new BornEntity(new Position(-2125f, 138f, -4465f));
        var logs = capture(Level.WARN);
        try {
            var pos =
                    ActionSummon.resolveSummonPosition(
                            null, caster, bornAction("ConfigBornByGlobalValue", "SplitPos1"), NO_POSITION);

            assertEquals(-2125f, pos.getX(), 1e-4f);
            assertEquals(138f, pos.getY(), 1e-4f);
            assertEquals(-4465f, pos.getZ(), 1e-4f);
            assertNotEquals(0f, pos.getX(), "the old fallback put every minion at the world origin");
            assertTrue(
                    messages(logs).stream()
                            .anyMatch(m -> m.contains("SplitPos1") && m.contains("cannot resolve global position")),
                    "the miss has to be traceable: " + messages(logs));
        } finally {
            release(logs);
        }
    }

    @Test
    void aBornBlockWithoutAPositionKeyAlsoAvoidsTheOrigin() {
        var caster = new BornEntity(new Position(5f, 6f, 7f));

        var pos =
                ActionSummon.resolveSummonPosition(
                        null, caster, bornAction("ConfigBornByGlobalValue", null), NO_POSITION);

        assertEquals(5f, pos.getX(), 1e-4f);
        assertEquals(7f, pos.getZ(), 1e-4f);
    }

    @Test
    void aBornTypeThatIsNotKeyedOnAValueKeepsTheClientsOwnPosition() {
        var caster = new BornEntity(new Position(5f, 6f, 7f));

        var pos =
                ActionSummon.resolveSummonPosition(
                        null,
                        caster,
                        bornAction("ConfigBornBySelf", "SplitPos1"),
                        Vector.newBuilder().setX(1f).setY(2f).setZ(3f).build());

        assertEquals(1f, pos.getX(), 1e-4f);
        assertEquals(2f, pos.getY(), 1e-4f);
        assertEquals(3f, pos.getZ(), 1e-4f);
    }

    // --- applyOffset ----------------------------------------------------------------------

    @Test
    void withoutOnGroundTheVerticalComponentIsHeightAsExpected() {
        var pos = new Position(10f, 20f, 30f);

        ActionSetGlobalPos.applyOffset(
                pos, Map.of("x", 1.0, "y", 2.0, "z", 3.0), false, "Test_Ability");

        assertEquals(11f, pos.getX(), 1e-4f);
        assertEquals(22f, pos.getY(), 1e-4f);
        assertEquals(33f, pos.getZ(), 1e-4f);
    }

    @Test
    void onGroundTurnsTheArraysFourOffsetsIntoFourCornersOfASquare() {
        var corners = new java.util.HashSet<String>();
        for (float dy : new float[] {7f, -7f}) {
            for (float dz : new float[] {7f, -7f}) {
                var pos = new Position(-2125f, 138f, -4465f);
                ActionSetGlobalPos.applyOffset(
                        pos, Map.of("y", (double) dy, "z", (double) dz), true, "Monster_Apparatus_Perpetual");
                corners.add(pos.getX() + "/" + pos.getY() + "/" + pos.getZ());
                // onGround means the caster's own height is the ground; nothing buries a minion.
                assertEquals(138f, pos.getY(), 1e-4f);
            }
        }

        assertEquals(4, corners.size(), "the split has to be four distinct ground points: " + corners);
    }

    @Test
    void anOffsetAlreadyUsingBothHorizontalAxesFoldsIntoXAndRecordsTheGuess() throws Exception {
        var logs = capture(Level.DEBUG);
        try {
            var pos = new Position();
            ActionSetGlobalPos.applyOffset(
                    pos, Map.of("x", 1.0, "z", 2.0, "y", 3.464), true, "Monster_Abyss_Fire");

            assertEquals(4.464f, pos.getX(), 1e-3f);
            assertEquals(2f, pos.getZ(), 1e-4f);
            assertEquals(0f, pos.getY(), 1e-4f);
            assertTrue(
                    messages(logs).stream().anyMatch(m -> m.contains("folding y into x")),
                    "the ambiguity must be in the log: " + messages(logs));
        } finally {
            release(logs);
        }
    }

    @Test
    void whenOnlyXIsUsedTheLateralFoldGoesToZ() {
        var pos = new Position();

        ActionSetGlobalPos.applyOffset(pos, Map.of("x", 1.0, "y", 2.0), true, "Test_Ability");

        assertEquals(1f, pos.getX(), 1e-4f);
        assertEquals(2f, pos.getZ(), 1e-4f);
    }

    // --- helpers --------------------------------------------------------------------------

    private static AbilityModifierAction bornAction(String bornType, String positionKey) {
        var action = new AbilityModifierAction();
        var born = new HashMap<String, Object>();
        born.put("$type", bornType);
        if (positionKey != null) born.put("positionKey", positionKey);
        action.born = born;
        return action;
    }

    private static ListAppender<ILoggingEvent> capture(Level level) {
        var appender = new ListAppender<ILoggingEvent>();
        var logger = Grasscutter.getLogger();
        logger.setLevel(level);
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static List<String> messages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static void release(ListAppender<ILoggingEvent> appender) {
        var logger = Grasscutter.getLogger();
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(null);
    }

    /** A GameEntity that only needs a position and the global position map. */
    private static final class BornEntity extends GameEntity {
        private final Position position;

        private BornEntity(Position position) {
            super((Scene) null);
            this.position = position;
        }

        @Override
        public Position getPosition() {
            return position;
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
        public Position getRotation() {
            return new Position();
        }

        @Override
        public SceneEntityInfo toProto() {
            return SceneEntityInfo.getDefaultInstance();
        }
    }
}
