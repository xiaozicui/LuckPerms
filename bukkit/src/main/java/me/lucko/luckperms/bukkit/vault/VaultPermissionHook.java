/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.bukkit.vault;

import lombok.Getter;
import lombok.NonNull;

import me.lucko.luckperms.api.Contexts;
import me.lucko.luckperms.api.DataMutateResult;
import me.lucko.luckperms.api.Node;
import me.lucko.luckperms.api.Tristate;
import me.lucko.luckperms.api.caching.PermissionData;
import me.lucko.luckperms.api.context.ImmutableContextSet;
import me.lucko.luckperms.api.context.MutableContextSet;
import me.lucko.luckperms.bukkit.LPBukkitPlugin;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.core.NodeFactory;
import me.lucko.luckperms.common.core.model.Group;
import me.lucko.luckperms.common.core.model.PermissionHolder;
import me.lucko.luckperms.common.core.model.User;
import me.lucko.luckperms.common.utils.ExtractedContexts;

import net.milkbowl.vault.permission.Permission;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LuckPerms Vault Permission implementation
 * Most lookups are cached.
 */
@Getter
public class VaultPermissionHook extends Permission {
    private LPBukkitPlugin plugin;
    private VaultScheduler scheduler;

    private final String name = "LuckPerms";

    private Function<String, String> worldCorrectionFunction = s -> isIgnoreWorld() ? null : s;

    public VaultPermissionHook(LPBukkitPlugin plugin) {
        this.plugin = plugin;
        this.scheduler = new VaultScheduler(plugin);
        super.plugin = plugin;
    }

    public void log(String s) {
        if (plugin.getConfiguration().get(ConfigKeys.VAULT_DEBUG)) {
            plugin.getLog().info("[VAULT] " + s);
        }
    }

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled();
    }

    @Override
    public boolean hasSuperPermsCompat() {
        return true;
    }

    /**
     * Generic method to add a permission to a holder
     *
     * @param world      the world to add in
     * @param holder     the holder to add the permission to
     * @param permission the permission to add
     */
    private CompletableFuture<Void> add(String world, PermissionHolder holder, String permission) {
        return CompletableFuture.runAsync(() -> {
            DataMutateResult result;
            if (world != null && !world.equals("") && !world.equalsIgnoreCase("global")) {
                result = holder.setPermission(NodeFactory.make(permission, true, getServer(), world));
            } else {
                result = holder.setPermission(NodeFactory.make(permission, true, getServer()));
            }

            if (result.asBoolean()) {
                save(holder);
            }
        }, scheduler);
    }

    /**
     * Generic method to remove a permission from a holder
     *
     * @param world      the world to remove in
     * @param holder     the holder to remove the permission from
     * @param permission the permission to remove
     */
    private CompletableFuture<Void> remove(String world, PermissionHolder holder, String permission) {
        return CompletableFuture.runAsync(() -> {
            DataMutateResult result;
            if (world != null && !world.equals("") && !world.equalsIgnoreCase("global")) {
                result = holder.unsetPermission(NodeFactory.make(permission, getServer(), world));
            } else {
                result = holder.unsetPermission(NodeFactory.make(permission, getServer()));
            }

            if (result.asBoolean()) {
                save(holder);
            }
        }, scheduler);
    }

    /**
     * Utility method to asynchronously save a user or group
     *
     * @param holder the holder instance
     */
    public void save(PermissionHolder holder) {
        if (holder instanceof User) {
            User u = (User) holder;
            plugin.getStorage().saveUser(u).thenRunAsync(() -> u.getRefreshBuffer().request(), plugin.getScheduler().async());
        }
        if (holder instanceof Group) {
            Group g = (Group) holder;
            plugin.getStorage().saveGroup(g).thenRunAsync(() -> plugin.getUpdateTaskBuffer().request(), plugin.getScheduler().async());
        }
    }

    public Contexts createContextForWorldSet(String world) {
        MutableContextSet context = MutableContextSet.create();
        if (world != null && !world.equals("") && !world.equalsIgnoreCase("global")) {
            context.add("world", world);
        }
        context.add("server", getServer());
        return new Contexts(context, isIncludeGlobal(), true, true, true, true, false);
    }

    public Contexts createContextForWorldLookup(String world) {
        MutableContextSet context = MutableContextSet.create();
        if (world != null && !world.equals("") && !world.equalsIgnoreCase("global")) {
            context.add("world", world);
        }
        context.add("server", getServer());
        context.addAll(plugin.getConfiguration().getContextsFile().getStaticContexts());
        return new Contexts(context, isIncludeGlobal(), true, true, true, true, false);
    }

    public Contexts createContextForWorldLookup(Player player, String world) {
        MutableContextSet context = MutableContextSet.create();

        // use player context
        if (player != null) {
            ImmutableContextSet applicableContext = plugin.getContextManager().getApplicableContext(player);
            context.addAll(applicableContext);
        } else {
            // at least given them the static context defined for this instance
            context.addAll(plugin.getConfiguration().getContextsFile().getStaticContexts());
        }

        // worlds & servers get set depending on the config setting
        context.removeAll("world");
        context.removeAll("server");

        // add the vault settings
        if (world != null && !world.equals("") && !world.equalsIgnoreCase("global")) {
            context.add("world", world);
        }
        context.add("server", getServer());

        return new Contexts(context, isIncludeGlobal(), true, true, true, true, false);
    }

    @Override
    public boolean playerHas(String world, @NonNull String player, @NonNull String permission) {
        world = worldCorrectionFunction.apply(world);
        log("Checking if player " + player + " has permission: " + permission + " on world " + world + ", server " + getServer());

        User user = plugin.getUserManager().getByUsername(player);
        if (user == null) return false;

        // Effectively fallback to the standard Bukkit #hasPermission check.
        return user.getUserData().getPermissionData(createContextForWorldLookup(plugin.getPlayer(user), world)).getPermissionValue(permission).asBoolean();
    }

    @Override
    public boolean playerAdd(String world, @NonNull String player, @NonNull String permission) {
        world = worldCorrectionFunction.apply(world);
        log("Adding permission to player " + player + ": '" + permission + "' on world " + world + ", server " + getServer());

        final User user = plugin.getUserManager().getByUsername(player);
        if (user == null) return false;

        add(world, user, permission);
        return true;
    }

    @Override
    public boolean playerRemove(String world, @NonNull String player, @NonNull String permission) {
        world = worldCorrectionFunction.apply(world);
        log("Removing permission from player " + player + ": '" + permission + "' on world " + world + ", server " + getServer());

        final User user = plugin.getUserManager().getByUsername(player);
        if (user == null) return false;

        remove(world, user, permission);
        return true;
    }

    @Override
    public boolean groupHas(String world, @NonNull String groupName, @NonNull String permission) {
        world = worldCorrectionFunction.apply(world);
        log("Checking if group " + groupName + " has permission: " + permission + " on world " + world + ", server " + getServer());

        final Group group = plugin.getGroupManager().getIfLoaded(groupName);
        if (group == null) return false;

        // This is a nasty call. Groups aren't cached. :(
        Map<String, Boolean> permissions = group.exportNodes(ExtractedContexts.generate(createContextForWorldLookup(world)), true);
        return permissions.containsKey(permission.toLowerCase()) && permissions.get(permission.toLowerCase());
    }

    @Override
    public boolean groupAdd(String world, @NonNull String groupName, @NonNull String permission) {
        world = worldCorrectionFunction.apply(world);
        log("Adding permission to group " + groupName + ": '" + permission + "' on world " + world + ", server " + getServer());

        final Group group = plugin.getGroupManager().getIfLoaded(groupName);
        if (group == null) return false;

        add(world, group, permission);
        return true;
    }

    @Override
    public boolean groupRemove(String world, @NonNull String groupName, @NonNull String permission) {
        world = worldCorrectionFunction.apply(world);
        log("Removing permission from group " + groupName + ": '" + permission + "' on world " + world + ", server " + getServer());

        final Group group = plugin.getGroupManager().getIfLoaded(groupName);
        if (group == null) return false;

        remove(world, group, permission);
        return true;
    }

    @Override
    public boolean playerInGroup(String world, @NonNull String player, @NonNull String group) {
        world = worldCorrectionFunction.apply(world);
        log("Checking if player " + player + " is in group: " + group + " on world " + world + ", server " + getServer());

        final User user = plugin.getUserManager().getByUsername(player);
        if (user == null) return false;

        String w = world; // screw effectively final
        return user.getNodes().values().stream()
                .filter(Node::isGroupNode)
                .filter(n -> n.shouldApplyWithContext(createContextForWorldLookup(plugin.getPlayer(user), w).getContexts()))
                .map(Node::getGroupName)
                .anyMatch(s -> s.equalsIgnoreCase(group));
    }

    @Override
    public boolean playerAddGroup(String world, @NonNull String player, @NonNull String groupName) {
        world = worldCorrectionFunction.apply(world);
        log("Adding player " + player + " to group: '" + groupName + "' on world " + world + ", server " + getServer());

        final User user = plugin.getUserManager().getByUsername(player);
        if (user == null) return false;

        final Group group = plugin.getGroupManager().getIfLoaded(groupName);
        if (group == null) return false;

        String w = world;
        scheduler.execute(() -> {
            DataMutateResult result;
            if (w != null && !w.equals("") && !w.equalsIgnoreCase("global")) {
                result = user.setInheritGroup(group, ImmutableContextSet.of("server", getServer(), "world", w));
            } else {
                result = user.setInheritGroup(group, ImmutableContextSet.singleton("server", getServer()));
            }

            if (result.asBoolean()) {
                save(user);
            }
        });
        return true;
    }

    @Override
    public boolean playerRemoveGroup(String world, @NonNull String player, @NonNull String groupName) {
        world = worldCorrectionFunction.apply(world);
        log("Removing player " + player + " from group: '" + groupName + "' on world " + world + ", server " + getServer());

        final User user = plugin.getUserManager().getByUsername(player);
        if (user == null) return false;

        final Group group = plugin.getGroupManager().getIfLoaded(groupName);
        if (group == null) return false;

        String w = world;
        scheduler.execute(() -> {
            DataMutateResult result;
            if (w != null && !w.equals("") && !w.equalsIgnoreCase("global")) {
                result = user.unsetInheritGroup(group, ImmutableContextSet.of("server", getServer(), "world", w));
            } else {
                result = user.unsetInheritGroup(group, ImmutableContextSet.singleton("server", getServer()));
            }

            if (result.asBoolean()) {
                save(user);
            }
        });
        return true;
    }

    @Override
    public String[] getPlayerGroups(String world, @NonNull String player) {
        world = worldCorrectionFunction.apply(world);
        log("Getting groups of player: " + player + ", on world " + world + ", server " + getServer());

        User user = plugin.getUserManager().getByUsername(player);
        if (user == null) return new String[0];

        String w = world; // screw effectively final
        return user.getNodes().values().stream()
                .filter(Node::isGroupNode)
                .filter(n -> n.shouldApplyWithContext(createContextForWorldLookup(plugin.getPlayer(user), w).getContexts()))
                .map(Node::getGroupName)
                .toArray(String[]::new);
    }

    @Override
    public String getPrimaryGroup(String world, @NonNull String player) {
        world = worldCorrectionFunction.apply(world);
        log("Getting primary group of player: " + player);
        final User user = plugin.getUserManager().getByUsername(player);

        if (user == null) {
            return null;
        }

        // nothing special, just return the value.
        if (!isPgo()) {
            String g = user.getPrimaryGroup().getValue();
            return plugin.getConfiguration().get(ConfigKeys.GROUP_NAME_REWRITES).getOrDefault(g, g);
        }

        // we need to do the complex PGO checking. (it's been enabled in the config.)
        if (isPgoCheckInherited()) {
            // we can just check the cached data
            PermissionData data = user.getUserData().getPermissionData(createContextForWorldLookup(plugin.getPlayer(user), world));
            for (Map.Entry<String, Boolean> e : data.getImmutableBacking().entrySet()) {
                if (!e.getValue()) continue;
                if (!e.getKey().toLowerCase().startsWith("vault.primarygroup.")) continue;

                String group = e.getKey().substring("vault.primarygroup.".length());
                if (isPgoCheckExists()) {
                    if (!plugin.getGroupManager().isLoaded(group)) {
                        continue;
                    }
                }

                if (isPgoCheckMemberOf()) {
                    if (data.getPermissionValue("group." + group) != Tristate.TRUE) {
                        continue;
                    }
                }

                return group;
            }
        } else {
            // we need to check the users permissions only
            for (Node node : user.mergePermissionsToList()) {
                if (!node.getValue()) continue;
                if (!node.getPermission().toLowerCase().startsWith("vault.primarygroup.")) continue;
                if (!node.shouldApplyOnServer(getServer(), isIncludeGlobal(), false)) continue;
                if (!node.shouldApplyOnWorld(world, true, false)) continue;

                String group = node.getPermission().substring("vault.primarygroup.".length());
                if (isPgoCheckExists()) {
                    if (!plugin.getGroupManager().isLoaded(group)) {
                        continue;
                    }
                }

                if (isPgoCheckMemberOf()) {
                    String finalWorld = world;
                    List<String> localGroups = user.mergePermissionsToList().stream()
                            .filter(Node::isGroupNode)
                            .filter(n -> n.shouldApplyOnWorld(finalWorld, isIncludeGlobal(), true))
                            .filter(n -> n.shouldApplyOnServer(getServer(), isIncludeGlobal(), true))
                            .map(Node::getGroupName)
                            .collect(Collectors.toList());

                    if (!localGroups.contains(group.toLowerCase())) {
                        continue;
                    }
                }

                return group;
            }
        }

        // Fallback
        String g = user.getPrimaryGroup().getValue();
        return plugin.getConfiguration().get(ConfigKeys.GROUP_NAME_REWRITES).getOrDefault(g, g);
    }

    @Override
    public String[] getGroups() {
        return plugin.getGroupManager().getAll().keySet().toArray(new String[0]);
    }

    @Override
    public boolean hasGroupSupport() {
        return true;
    }

    String getServer() {
        return plugin.getConfiguration().get(ConfigKeys.VAULT_SERVER);
    }

    boolean isIncludeGlobal() {
        return plugin.getConfiguration().get(ConfigKeys.VAULT_INCLUDING_GLOBAL);
    }

    boolean isIgnoreWorld() {
        return plugin.getConfiguration().get(ConfigKeys.VAULT_IGNORE_WORLD);
    }

    private boolean isPgo() {
        return plugin.getConfiguration().get(ConfigKeys.VAULT_PRIMARY_GROUP_OVERRIDES);
    }

    private boolean isPgoCheckInherited() {
        return plugin.getConfiguration().get(ConfigKeys.VAULT_PRIMARY_GROUP_OVERRIDES_CHECK_INHERITED);
    }

    private boolean isPgoCheckExists() {
        return plugin.getConfiguration().get(ConfigKeys.VAULT_PRIMARY_GROUP_OVERRIDES_CHECK_EXISTS);
    }

    private boolean isPgoCheckMemberOf() {
        return plugin.getConfiguration().get(ConfigKeys.VAULT_PRIMARY_GROUP_OVERRIDES_CHECK_MEMBER_OF);
    }
}
