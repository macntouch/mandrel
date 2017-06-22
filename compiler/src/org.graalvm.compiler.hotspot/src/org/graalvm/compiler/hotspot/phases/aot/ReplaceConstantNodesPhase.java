/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.hotspot.phases.aot;

import static org.graalvm.compiler.core.common.cfg.AbstractControlFlowGraph.strictlyDominates;
import static org.graalvm.compiler.hotspot.nodes.aot.LoadMethodCountersNode.getLoadMethodCountersNodes;
import static org.graalvm.compiler.nodes.ConstantNode.getConstantNodes;

import java.util.HashSet;
import java.util.List;

import jdk.vm.ci.hotspot.HotSpotMetaspaceConstant;
import jdk.vm.ci.hotspot.HotSpotObjectConstant;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaType;
import jdk.vm.ci.hotspot.HotSpotResolvedObjectType;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.ResolvedJavaType;

import org.graalvm.compiler.core.common.cfg.BlockMap;
import org.graalvm.compiler.core.common.type.ObjectStamp;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.NodeMap;
import org.graalvm.compiler.hotspot.FingerprintUtil;
import org.graalvm.compiler.hotspot.meta.HotSpotConstantLoadAction;
import org.graalvm.compiler.hotspot.nodes.aot.InitializeKlassNode;
import org.graalvm.compiler.hotspot.nodes.aot.LoadConstantIndirectlyFixedNode;
import org.graalvm.compiler.hotspot.nodes.aot.LoadConstantIndirectlyNode;
import org.graalvm.compiler.hotspot.nodes.aot.LoadMethodCountersNode;
import org.graalvm.compiler.hotspot.nodes.aot.ResolveConstantNode;
import org.graalvm.compiler.hotspot.nodes.aot.ResolveMethodAndLoadCountersNode;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.FixedWithNextNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.StructuredGraph.ScheduleResult;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.cfg.Block;
import org.graalvm.compiler.phases.BasePhase;
import org.graalvm.compiler.phases.schedule.SchedulePhase;
import org.graalvm.compiler.phases.schedule.SchedulePhase.SchedulingStrategy;
import org.graalvm.compiler.phases.tiers.PhaseContext;
import org.graalvm.util.EconomicMap;

public class ReplaceConstantNodesPhase extends BasePhase<PhaseContext> {

    private static final HashSet<Class<?>> builtIns = new HashSet<>();
    private final boolean verifyFingerprints;

    static {
        builtIns.add(Boolean.class);

        Class<?> characterCacheClass = Character.class.getDeclaredClasses()[0];
        assert "java.lang.Character$CharacterCache".equals(characterCacheClass.getName());
        builtIns.add(characterCacheClass);

        Class<?> byteCacheClass = Byte.class.getDeclaredClasses()[0];
        assert "java.lang.Byte$ByteCache".equals(byteCacheClass.getName());
        builtIns.add(byteCacheClass);

        Class<?> shortCacheClass = Short.class.getDeclaredClasses()[0];
        assert "java.lang.Short$ShortCache".equals(shortCacheClass.getName());
        builtIns.add(shortCacheClass);

        Class<?> integerCacheClass = Integer.class.getDeclaredClasses()[0];
        assert "java.lang.Integer$IntegerCache".equals(integerCacheClass.getName());
        builtIns.add(integerCacheClass);

        Class<?> longCacheClass = Long.class.getDeclaredClasses()[0];
        assert "java.lang.Long$LongCache".equals(longCacheClass.getName());
        builtIns.add(longCacheClass);
    }

    private static boolean isReplacementNode(Node n) {
        // @formatter:off
        return n instanceof LoadConstantIndirectlyNode      ||
               n instanceof LoadConstantIndirectlyFixedNode ||
               n instanceof ResolveConstantNode             ||
               n instanceof InitializeKlassNode;
        // @formatter:on
    }

    private static boolean anyUsagesNeedReplacement(ConstantNode node) {
        return node.usages().filter(n -> !isReplacementNode(n)).isNotEmpty();
    }

    private static boolean anyUsagesNeedReplacement(LoadMethodCountersNode node) {
        return node.usages().filter(n -> !(n instanceof ResolveMethodAndLoadCountersNode)).isNotEmpty();
    }

    private static boolean checkForBadFingerprint(HotSpotResolvedJavaType type) {
        if (type.isArray()) {
            if (type.getElementalType().isPrimitive()) {
                return false;
            }
            return FingerprintUtil.getFingerprint((HotSpotResolvedObjectType) (type.getElementalType())) == 0;
        }
        return FingerprintUtil.getFingerprint((HotSpotResolvedObjectType) type) == 0;
    }

    /**
     * Replace {@link ConstantNode} containing a {@link HotSpotResolvedJavaType} with indirection.
     *
     * @param graph
     * @param node {@link ConstantNode} containing a {@link HotSpotResolvedJavaType} that needs
     *            resolution.
     */
    private void handleHotSpotMetaspaceConstant(StructuredGraph graph, ConstantNode node) {
        HotSpotMetaspaceConstant metaspaceConstant = (HotSpotMetaspaceConstant) node.asConstant();
        HotSpotResolvedJavaType type = (HotSpotResolvedJavaType) metaspaceConstant.asResolvedJavaType();

        if (type != null) {
            if (verifyFingerprints && checkForBadFingerprint(type)) {
                throw new GraalError("Type with bad fingerprint: " + type);
            }
            assert !metaspaceConstant.isCompressed() : "No support for replacing compressed metaspace constants";
            tryToReplaceWithExisting(graph, node);
            if (anyUsagesNeedReplacement(node)) {
                replaceWithResolution(graph, node);
            }
        } else {
            throw new GraalError("Unsupported metaspace constant type: " + type);
        }
    }

    /**
     * Find the lowest dominating {@link FixedWithNextNode} before given node.
     *
     * @param graph
     * @param node
     * @return the last {@link FixedWithNextNode} that is scheduled before node.
     */
    private static FixedWithNextNode findFixedWithNextBefore(StructuredGraph graph, Node node) {
        ScheduleResult schedule = graph.getLastSchedule();
        NodeMap<Block> nodeToBlock = schedule.getNodeToBlockMap();
        BlockMap<List<Node>> blockToNodes = schedule.getBlockToNodesMap();

        Block block = nodeToBlock.get(node);
        FixedWithNextNode result = null;
        for (Node n : blockToNodes.get(block)) {
            if (n instanceof FixedWithNextNode) {
                result = (FixedWithNextNode) n;
            }
            if (n.equals(node)) {
                break;
            }
        }
        assert result != null;
        return result;
    }

    /**
     * Try to find dominating node doing the resolution that can be reused.
     *
     * @param graph
     * @param node {@link ConstantNode} containing a {@link HotSpotResolvedJavaType} that needs
     *            resolution.
     */
    private static void tryToReplaceWithExisting(StructuredGraph graph, ConstantNode node) {
        ScheduleResult schedule = graph.getLastSchedule();
        NodeMap<Block> nodeToBlock = schedule.getNodeToBlockMap();
        BlockMap<List<Node>> blockToNodes = schedule.getBlockToNodesMap();

        EconomicMap<Block, Node> blockToExisting = EconomicMap.create();
        for (Node n : node.usages().filter(n -> isReplacementNode(n))) {
            blockToExisting.put(nodeToBlock.get(n), n);
        }
        for (Node use : node.usages().filter(n -> !isReplacementNode(n)).snapshot()) {
            boolean replaced = false;
            Block b = nodeToBlock.get(use);
            Node e = blockToExisting.get(b);
            if (e != null) {
                // There is an initialization or resolution in the same block as the use, look if
                // the use is scheduled after it.
                for (Node n : blockToNodes.get(b)) {
                    if (n.equals(use)) {
                        // Usage is before initialization, can't use it
                        break;
                    }
                    if (n.equals(e)) {
                        use.replaceFirstInput(node, e);
                        replaced = true;
                        break;
                    }
                }
            }
            if (!replaced) {
                // Look for dominating blocks that have existing nodes
                for (Block d : blockToExisting.getKeys()) {
                    if (strictlyDominates(d, b)) {
                        use.replaceFirstInput(node, blockToExisting.get(d));
                        break;
                    }
                }
            }
        }
    }

    /**
     * Replace the uses of a constant with either {@link LoadConstantIndirectlyNode} or
     * {@link ResolveConstantNode}.
     *
     * @param graph
     * @param node {@link ConstantNode} containing a {@link HotSpotResolvedJavaType} that needs
     *            resolution.
     */
    private static void replaceWithResolution(StructuredGraph graph, ConstantNode node) {
        HotSpotMetaspaceConstant metaspaceConstant = (HotSpotMetaspaceConstant) node.asConstant();
        HotSpotResolvedJavaType type = (HotSpotResolvedJavaType) metaspaceConstant.asResolvedJavaType();
        ResolvedJavaType topMethodHolder = graph.method().getDeclaringClass();
        ValueNode replacement;

        if (type.isArray() && type.getComponentType().isPrimitive()) {
            // Special case for primitive arrays. The AOT runtime pre-resolves them, so we may
            // omit the resolution call.
            replacement = graph.addOrUnique(new LoadConstantIndirectlyNode(node));
        } else if (type.equals(topMethodHolder) || (type.isAssignableFrom(topMethodHolder) && !type.isInterface())) {
            // If it's a supertype of or the same class that declares the top method, we are
            // guaranteed to have it resolved already. If it's an interface, we just test for
            // equality.
            replacement = graph.addOrUnique(new LoadConstantIndirectlyNode(node));
        } else {
            FixedWithNextNode fixedReplacement;
            if (builtIns.contains(type.mirror())) {
                // Special case of klass constants that come from {@link BoxingSnippets}.
                fixedReplacement = graph.add(new ResolveConstantNode(node, HotSpotConstantLoadAction.INITIALIZE));
            } else {
                fixedReplacement = graph.add(new ResolveConstantNode(node));
            }
            graph.addAfterFixed(findFixedWithNextBefore(graph, node), fixedReplacement);
            replacement = fixedReplacement;
        }
        node.replaceAtUsages(replacement, n -> !isReplacementNode(n));
    }

    /**
     * Replace an object constant with an indirect load {@link ResolveConstantNode}. Currently we
     * support only strings.
     *
     * @param graph
     * @param node {@link ConstantNode} containing a {@link HotSpotObjectConstant} that needs
     *            resolution.
     */
    private static void handleHotSpotObjectConstant(StructuredGraph graph, ConstantNode node) {
        HotSpotObjectConstant constant = (HotSpotObjectConstant) node.asJavaConstant();
        HotSpotResolvedJavaType type = (HotSpotResolvedJavaType) constant.getType();
        if (type.mirror().equals(String.class)) {
            assert !constant.isCompressed() : "No support for replacing compressed oop constants";
            FixedWithNextNode replacement = graph.add(new ResolveConstantNode(node));
            graph.addAfterFixed(findFixedWithNextBefore(graph, node), replacement);
            node.replaceAtUsages(replacement, n -> !(n instanceof ResolveConstantNode));
        } else {
            throw new GraalError("Unsupported object constant type: " + type);
        }
    }

    /**
     * Replace {@link LoadMethodCountersNode} with indirect load
     * {@link ResolveMethodAndLoadCountersNode}, expose a klass constant of the holder.
     *
     * @param graph
     * @param node
     * @param context
     */
    private static void handleLoadMethodCounters(StructuredGraph graph, LoadMethodCountersNode node, PhaseContext context) {
        ResolvedJavaType type = node.getMethod().getDeclaringClass();
        Stamp hubStamp = context.getStampProvider().createHubStamp((ObjectStamp) StampFactory.objectNonNull());
        ConstantReflectionProvider constantReflection = context.getConstantReflection();
        ConstantNode klassHint = ConstantNode.forConstant(hubStamp, constantReflection.asObjectHub(type), context.getMetaAccess(), graph);
        FixedWithNextNode replacement = graph.add(new ResolveMethodAndLoadCountersNode(node.getMethod(), klassHint));
        graph.addAfterFixed(findFixedWithNextBefore(graph, node), replacement);
        node.replaceAtUsages(replacement, n -> !(n instanceof ResolveMethodAndLoadCountersNode));
    }

    /**
     * Replace {@link LoadMethodCountersNode} with {@link ResolveMethodAndLoadCountersNode}, expose
     * klass constants.
     *
     * @param graph
     * @param context
     */
    private static void replaceLoadMethodCounters(StructuredGraph graph, PhaseContext context) {
        new SchedulePhase(SchedulingStrategy.LATEST_OUT_OF_LOOPS, true).apply(graph, false);
        for (LoadMethodCountersNode node : getLoadMethodCountersNodes(graph)) {
            if (anyUsagesNeedReplacement(node)) {
                handleLoadMethodCounters(graph, node, context);
            }
        }
    }

    /**
     * Replace object and klass constants with resolution nodes or reuse preceding initializations.
     *
     * @param graph
     */
    private void replaceKlassesAndObjects(StructuredGraph graph) {
        new SchedulePhase(SchedulingStrategy.LATEST_OUT_OF_LOOPS, true).apply(graph, false);

        for (ConstantNode node : getConstantNodes(graph)) {
            Constant constant = node.asConstant();
            if (constant instanceof HotSpotMetaspaceConstant && anyUsagesNeedReplacement(node)) {
                handleHotSpotMetaspaceConstant(graph, node);
            } else if (constant instanceof HotSpotObjectConstant && anyUsagesNeedReplacement(node)) {
                handleHotSpotObjectConstant(graph, node);
            }
        }
    }

    @Override
    protected void run(StructuredGraph graph, PhaseContext context) {
        // Replace LoadMethodCountersNode with ResolveMethodAndLoadCountersNode, expose klass
        // constants.
        replaceLoadMethodCounters(graph, context);

        // Replace object and klass constants (including the ones added in the previous pass) with
        // resolution nodes.
        replaceKlassesAndObjects(graph);
    }

    @Override
    public boolean checkContract() {
        return false;
    }

    public ReplaceConstantNodesPhase() {
        this(true);
    }

    public ReplaceConstantNodesPhase(boolean verifyFingerprints) {
        this.verifyFingerprints = verifyFingerprints;
    }
}
