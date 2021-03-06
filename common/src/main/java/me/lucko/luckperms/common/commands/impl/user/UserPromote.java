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

package me.lucko.luckperms.common.commands.impl.user;

import me.lucko.luckperms.api.Node;
import me.lucko.luckperms.api.context.MutableContextSet;
import me.lucko.luckperms.common.commands.CommandException;
import me.lucko.luckperms.common.commands.CommandResult;
import me.lucko.luckperms.common.commands.abstraction.SubCommand;
import me.lucko.luckperms.common.commands.sender.Sender;
import me.lucko.luckperms.common.commands.utils.ArgumentUtils;
import me.lucko.luckperms.common.commands.utils.Util;
import me.lucko.luckperms.common.constants.DataConstraints;
import me.lucko.luckperms.common.constants.Permission;
import me.lucko.luckperms.common.core.NodeFactory;
import me.lucko.luckperms.common.core.model.Group;
import me.lucko.luckperms.common.core.model.Track;
import me.lucko.luckperms.common.core.model.User;
import me.lucko.luckperms.common.data.LogEntry;
import me.lucko.luckperms.common.locale.CommandSpec;
import me.lucko.luckperms.common.locale.LocaleManager;
import me.lucko.luckperms.common.locale.Message;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;
import me.lucko.luckperms.common.utils.Predicates;
import me.lucko.luckperms.exceptions.ObjectLacksException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class UserPromote extends SubCommand<User> {
    public UserPromote(LocaleManager locale) {
        super(CommandSpec.USER_PROMOTE.spec(locale), "promote", Permission.USER_PROMOTE, Predicates.is(0));
    }

    @Override
    public CommandResult execute(LuckPermsPlugin plugin, Sender sender, User user, List<String> args, String label) throws CommandException {
        final String trackName = args.get(0).toLowerCase();
        if (!DataConstraints.TRACK_NAME_TEST.test(trackName)) {
            Message.TRACK_INVALID_ENTRY.send(sender);
            return CommandResult.INVALID_ARGS;
        }

        if (!plugin.getStorage().loadTrack(trackName).join()) {
            Message.TRACK_DOES_NOT_EXIST.send(sender);
            return CommandResult.INVALID_ARGS;
        }

        Track track = plugin.getTrackManager().getIfLoaded(trackName);
        if (track == null) {
            Message.TRACK_DOES_NOT_EXIST.send(sender);
            return CommandResult.LOADING_ERROR;
        }

        if (track.getSize() <= 1) {
            Message.TRACK_EMPTY.send(sender);
            return CommandResult.STATE_ERROR;
        }

        boolean silent = args.remove("-s");
        MutableContextSet context = ArgumentUtils.handleContext(1, args, plugin);

        // Load applicable groups
        Set<Node> nodes = user.getNodes().values().stream()
                .filter(Node::isGroupNode)
                .filter(Node::getValue)
                .filter(node -> node.getFullContexts().makeImmutable().equals(context.makeImmutable()))
                .collect(Collectors.toSet());

        nodes.removeIf(g -> !track.containsGroup(g.getGroupName()));

        if (nodes.isEmpty()) {
            String first = track.getGroups().get(0);

            Group nextGroup = plugin.getGroupManager().getIfLoaded(first);
            if (nextGroup == null) {
                Message.USER_PROMOTE_ERROR_MALFORMED.send(sender, first);
                return CommandResult.LOADING_ERROR;
            }

            user.setPermission(NodeFactory.newBuilder("group." + first).withExtraContext(context).build());

            Message.USER_TRACK_ADDED_TO_FIRST.send(sender, user.getFriendlyName(), first, Util.contextSetToString(context));
            LogEntry.build().actor(sender).acted(user)
                    .action("promote " + args.stream().collect(Collectors.joining(" ")))
                    .build().submit(plugin, sender);
            save(user, sender, plugin);
            plugin.getApiProvider().getEventFactory().handleUserPromote(user, track, null, first);
            return CommandResult.SUCCESS;
        }

        if (nodes.size() != 1) {
            Message.TRACK_AMBIGUOUS_CALL.send(sender);
            return CommandResult.FAILURE;
        }

        final Node oldNode = nodes.stream().findAny().get();
        final String old = oldNode.getGroupName();
        final String next;
        try {
            next = track.getNext(old);
        } catch (ObjectLacksException e) {
            Message.TRACK_DOES_NOT_CONTAIN.send(sender, track.getName(), old);
            return CommandResult.STATE_ERROR;
        }

        if (next == null) {
            Message.USER_PROMOTE_ERROR_ENDOFTRACK.send(sender, track.getName());
            return CommandResult.STATE_ERROR;
        }

        if (!plugin.getStorage().loadGroup(next).join()) {
            Message.USER_PROMOTE_ERROR_MALFORMED.send(sender, next);
            return CommandResult.STATE_ERROR;
        }

        Group nextGroup = plugin.getGroupManager().getIfLoaded(next);
        if (nextGroup == null) {
            Message.USER_PROMOTE_ERROR_MALFORMED.send(sender, next);
            return CommandResult.LOADING_ERROR;
        }

        user.unsetPermission(oldNode);
        user.setPermission(NodeFactory.newBuilder("group." + nextGroup.getName()).withExtraContext(context).build());

        if (context.isEmpty() && user.getPrimaryGroup().getStoredValue().equalsIgnoreCase(old)) {
            user.getPrimaryGroup().setStoredValue(nextGroup.getName());
        }

        Message.USER_PROMOTE_SUCCESS.send(sender, track.getName(), old, nextGroup.getDisplayName(), Util.contextSetToString(context));
        if (!silent) {
            Message.EMPTY.send(sender, Util.listToArrowSep(track.getGroups(), old, nextGroup.getDisplayName(), false));
        }

        LogEntry.build().actor(sender).acted(user)
                .action("promote " + args.stream().collect(Collectors.joining(" ")))
                .build().submit(plugin, sender);

        save(user, sender, plugin);
        plugin.getApiProvider().getEventFactory().handleUserPromote(user, track, old, nextGroup.getName());
        return CommandResult.SUCCESS;
    }

    @Override
    public List<String> tabComplete(LuckPermsPlugin plugin, Sender sender, List<String> args) {
        return getTrackTabComplete(args, plugin);
    }
}
