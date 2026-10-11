/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.alto.command;

import baritone.alto.Alto;
import baritone.alto.script.ScriptReport;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** #script: run Alto scripts from baritone/alto/scripts (M5). */
final class ScriptCommand extends Command {
    private final Alto alto;

    ScriptCommand(IBaritone baritone, Alto alto) {
        super(baritone, "script");
        this.alto = alto;
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        AltoCommands.requireEnabled();
        String op = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
        var scripts = alto.scripts();
        switch (op) {
            case "list" -> {
                logDirect("Scripts in " + scripts.folder() + ": " + scripts.names());
            }
            case "run", "check" -> {
                args.requireMin(1);
                String name = args.getString();
                boolean confirm = args.hasAny() && args.getString().equalsIgnoreCase("confirm");
                if (op.equals("check")) {
                    try {
                        ScriptReport r = scripts.load(name);
                        if (!r.runnable()) {
                            logDirect("Errors:");
                            r.errors().forEach(d -> logDirect("  " + d));
                        } else {
                            scripts.describe(r).forEach(this::logDirect);
                        }
                    } catch (IOException e) {
                        throw new CommandInvalidStateException("Cannot read " + name + ": " + e.getMessage());
                    }
                    return;
                }
                List<String> problem = scripts.start(name, confirm);
                if (problem != null) {
                    problem.forEach(this::logDirect);
                }
            }
            case "stop" -> logDirect(scripts.stop("stopped with #script stop") ? "Script stopped." : "No script is running.");
            case "pause" -> logDirect(scripts.pause() ? "Script paused (#script resume)." : "No running script to pause.");
            case "resume" -> scripts.resume().forEach(this::logDirect);
            case "forget" -> {
                scripts.deleteSnapshot();
                logDirect("Forgot the saved script run.");
            }
            case "status" -> scripts.status().forEach(this::logDirect);
            case "gui", "screen" -> alto.openScriptScreen();
            default -> throw new CommandInvalidStateException("Unknown option " + op + "; see #help script");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return Stream.of("list", "run", "check", "stop", "pause", "resume", "status", "forget", "gui");
        }
        String op = args.getString();
        if (args.hasExactlyOne() && (op.equalsIgnoreCase("run") || op.equalsIgnoreCase("check"))) {
            return alto.scripts().names().stream();
        }
        return op.equalsIgnoreCase("run") ? Stream.of("confirm") : Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Run Alto scripts (made with the block editor)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Scripts are files baritone/alto/scripts/<name>.alto.json (made with alto-script-editor.html).",
                "A new or changed script first shows what it may do; run it again with 'confirm' to start it.",
                "Scripts never start by themselves. Your global stop key, #script stop or #stop end them.",
                "",
                "Usage:",
                "> script list",
                "> script check <name> - validate and show what it may do",
                "> script run <name> [confirm]",
                "> script pause / resume / stop / status",
                "> script resume - also continues a run saved before the game closed (asks nothing more)",
                "> script forget - forget a saved run",
                "> script gui - the script runner screen (also a key in Controls)"
        );
    }
}
