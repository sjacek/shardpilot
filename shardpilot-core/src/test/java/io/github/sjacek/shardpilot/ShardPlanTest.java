package io.github.sjacek.shardpilot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShardPlanTest {
    @Test
    void packsLongestFirstUsingTheLightestShard() {
        var plan = ShardPlan.create(Map.of("A", 10.0, "B", 9.0, "C", 6.0, "D", 5.0), 2);
        assertEquals(1, plan.shardFor("A"));
        assertEquals(2, plan.shardFor("B"));
        assertEquals(2, plan.shardFor("C"));
        assertEquals(1, plan.shardFor("D"));
        assertEquals(15.0, plan.recordedSeconds(1));
        assertEquals(15.0, plan.recordedSeconds(2));
    }

    @Test
    void equalWeightsUseClassNameAndLowestShardRegardlessOfMapOrder() {
        var reversed = new LinkedHashMap<String, Double>();
        reversed.put("C", 5.0);
        reversed.put("B", 5.0);
        reversed.put("A", 5.0);
        var plan = ShardPlan.create(reversed, 2);
        assertEquals(1, plan.shardFor("A"));
        assertEquals(2, plan.shardFor("B"));
        assertEquals(1, plan.shardFor("C"));
        reversed.clear();
        assertEquals(3, plan.recordedFamilies());
    }

    @Test
    void combinesNestedTimingsAndAssignsEveryNestedClassToItsOuterOwner() {
        var plan = ShardPlan.create(Map.of("example.Outer", 3.0, "example.Outer$Nested", 8.0,
                "example.Other", 10.0), 2);
        assertEquals(2, plan.recordedFamilies());
        assertEquals(1, plan.shardFor("example.Outer"));
        assertEquals(1, plan.shardFor("example.Outer$Nested$Deeper"));
        assertEquals(11.0, plan.recordedSeconds(1));
        assertEquals(2, plan.shardFor("example.Other"));
    }

    @Test
    void nestedWeightSumsAreStableEvenWhenFloatingPointAdditionWouldDependOnMapOrder() {
        var forward = new LinkedHashMap<String, Double>();
        forward.put("A", 1e16);
        forward.put("A$One", 1.0);
        forward.put("A$Two", 1.0);
        forward.put("B", 1e16 + 2);
        var reverse = new LinkedHashMap<String, Double>();
        reverse.put("B", 1e16 + 2);
        reverse.put("A$Two", 1.0);
        reverse.put("A$One", 1.0);
        reverse.put("A", 1e16);
        var first = ShardPlan.create(forward, 2);
        var second = ShardPlan.create(reverse, 2);
        assertEquals(first.shardFor("A"), second.shardFor("A"));
        assertEquals(first.shardFor("B"), second.shardFor("B"));
        assertEquals(first.recordedSeconds(1), second.recordedSeconds(1));
    }

    @Test
    void recordedLoadsStayIdenticalWhenRenamingFamiliesPreservesTheirOrderAndWeights() {
        var results = new HashSet<Double>();
        // Positive decimal samples from the CI failure expose encounter-order rounding in sum().
        // Renaming the common prefix preserves lexical order and weights but changes map hashes.
        for (int prefix = 0; prefix < 32; prefix++) {
            String family = "example.P" + prefix;
            var plan = ShardPlan.create(Map.of(family + "A", .051, family + "B", .004), 1);
            results.add(plan.recordedSeconds(1));
        }
        assertEquals(1, results.size(), "A shard's reported load must not depend on map hash positions: " + results);
    }

    @Test
    void coldStartAndNewClassesUseFloorModEvenForTheMinimumIntegerHash() {
        String minimumHash = "polygenelubricants";
        assertEquals(Integer.MIN_VALUE, minimumHash.hashCode());
        var plan = ShardPlan.create(Map.of("Known", 1.0), 7);
        assertEquals(Math.floorMod(minimumHash.hashCode(), 7) + 1, plan.shardFor(minimumHash));
        var cold = ShardPlan.create(Map.of(), 7);
        assertEquals(cold.shardFor("example.Unknown"), cold.shardFor("example.Unknown$Nested"));
    }

    @Test
    void allClassesHaveExactlyOneOwnerAcrossManyShardCounts() {
        var history = new LinkedHashMap<String, Double>();
        for (int number = 0; number < 100; number++) history.put("example.Test" + number, (double) (number % 13));
        for (int count : new int[] {1, 2, 3, 7, 128}) {
            var plan = ShardPlan.create(history, count);
            var assigned = new HashSet<String>();
            for (int shard = 1; shard <= count; shard++) {
                for (int number = 0; number < 150; number++) {
                    String name = "example.Test" + number;
                    if (plan.shardFor(name) == shard) assertTrue(assigned.add(name));
                }
            }
            assertEquals(150, assigned.size());
        }
    }

    @Test
    void largeShardCountsDoNotAllocateAnArrayPerShard() {
        var plan = ShardPlan.create(Map.of("A", 1.0), Integer.MAX_VALUE);
        assertEquals(1, plan.shardFor("A"));
        assertEquals(0.0, plan.recordedSeconds(Integer.MAX_VALUE));
    }

    @ParameterizedTest
    @CsvSource({"0,1", "-1,1", "2,0", "2,3"})
    void rejectsInvalidShardSettings(int count, int index) {
        assertThrows(IllegalArgumentException.class, () -> ShardPlan.validateShard(count, index));
    }

    @Test
    void rejectsNonFiniteNegativeAndOverflowedMeasurements() {
        for (double invalid : new double[] {-1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> ShardPlan.create(Map.of("A", invalid), 2));
        }
        assertThrows(IllegalArgumentException.class, () -> ShardPlan.create(
                Map.of("A", Double.MAX_VALUE, "A$Nested", Double.MAX_VALUE), 2));
        assertThrows(IllegalArgumentException.class, () -> ShardPlan.create(
                Map.of("A", Double.MAX_VALUE, "B", Double.MAX_VALUE), 1));
    }
}
