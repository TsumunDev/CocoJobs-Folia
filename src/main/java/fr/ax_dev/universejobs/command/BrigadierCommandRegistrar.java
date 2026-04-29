package fr.ax_dev.universejobs.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fr.ax_dev.universejobs.UniverseJobs;
import fr.ax_dev.universejobs.command.handler.*;
import fr.ax_dev.universejobs.config.LanguageManager;
import fr.ax_dev.universejobs.job.JobManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Registers all job commands using Paper's Brigadier system via LifecycleEvents.
 * This replaces the legacy CommandExecutor/TabCompleter pattern with the modern 2026 stack.
 *
 * NOTE: Some LSP errors may appear because the Paper API classes are not available in all IDEs.
 * These errors will NOT affect compilation when using the Paper API dependency from pom.xml.
 */
public class BrigadierCommandRegistrar {

    private final UniverseJobs plugin;
    private final LanguageManager languageManager;
    private final JobManager jobManager;

    // Command handlers
    private final JoinLeaveCommandHandler joinLeaveHandler;
    private final InfoStatsCommandHandler infoStatsHandler;
    private final RewardsCommandHandler rewardsHandler;
    private final ActionLimitCommandHandler actionLimitHandler;
    private final AdminJobCommandHandler adminJobHandler;
    private final MenuCommandHandler menuHandler;

    // Rate limiting for commands (per player)
    private final Map<UUID, Long> lastCommandTime = new HashMap<>();
    private static final long COMMAND_COOLDOWN_MS = 100; // 100ms between commands

    // Command constants
    private static final String CMD_JOIN = "join";
    private static final String CMD_LEAVE = "leave";
    private static final String CMD_INFO = "info";
    private static final String CMD_LIST = "list";
    private static final String CMD_STATS = "stats";
    private static final String CMD_REWARDS = "rewards";
    private static final String CMD_ACTION_LIMIT = "actionlimit";
    private static final String CMD_MENU = "menu";
    private static final String CMD_ADMIN = "admin";

    // Security patterns for input validation
    private static final Pattern COMMAND_INJECTION_PATTERN = Pattern.compile("[;&|`$(){}\\[\\]<>\"'\\\\]");

    /**
     * Create a new BrigadierCommandRegistrar.
     *
     * @param plugin The plugin instance
     */
    public BrigadierCommandRegistrar(UniverseJobs plugin) {
        this.plugin = plugin;
        this.languageManager = plugin.getLanguageManager();
        this.jobManager = plugin.getJobManager();

        // Initialize command handlers
        this.joinLeaveHandler = new JoinLeaveCommandHandler(plugin);
        this.infoStatsHandler = new InfoStatsCommandHandler(plugin);
        this.rewardsHandler = new RewardsCommandHandler(plugin);
        this.actionLimitHandler = new ActionLimitCommandHandler(plugin);
        this.adminJobHandler = new AdminJobCommandHandler(plugin, jobManager);
        this.menuHandler = new MenuCommandHandler(plugin);
    }

    /**
     * Register all commands via LifecycleEvents.
     * This method should be called from onEnable().
     */
    public void registerCommands() {
        plugin.getLogger().info("Registering Brigadier commands...");

        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, (event) -> {
            event.registrar().register(buildJobsCommand());
        });

        plugin.getLogger().info("Brigadier commands registered successfully!");
    }

    /**
     * Build the main /jobs command with all subcommands.
     */
    private LiteralCommandNode<CommandSourceStack> buildJobsCommand() {
        var jobsLiteral = Commands.literal("jobs")
                .requires(source -> source.getSender().hasPermission("universejobs.use"))
                .executes(ctx -> handleDefaultJobsCommand(ctx));

        jobsLiteral.then(buildJoinCommandLiteral());
        jobsLiteral.then(buildLeaveCommandLiteral());
        jobsLiteral.then(buildInfoCommandLiteral());
        jobsLiteral.then(buildListCommandLiteral());
        jobsLiteral.then(buildStatsCommandLiteral());
        jobsLiteral.then(buildRewardsCommandLiteral());
        jobsLiteral.then(buildActionLimitCommandLiteral());
        jobsLiteral.then(buildMenuCommandLiteral());
        jobsLiteral.then(buildAdminCommandLiteral());

        return jobsLiteral.build();
    }

    // ==================== Command Handlers ====================

    private int handleDefaultJobsCommand(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (sender instanceof Player player) {
            return menuHandler.handleCommand(sender, new String[0]) ? 1 : 0;
        } else {
            sendConsoleHelp(sender);
            return 1;
        }
    }

    // ==================== Subcommand Literal Builders ====================

    private LiteralCommandNode<CommandSourceStack> buildJoinCommandLiteral() {
        return Commands.literal(CMD_JOIN)
                .requires(source -> source.getSender() instanceof Player)
                .then(Commands.argument("job", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String input = builder.getRemaining().toLowerCase();
                            CommandSender sender = ctx.getSource().getSender();
                            if (sender instanceof Player player) {
                                for (fr.ax_dev.universejobs.job.Job job : jobManager.getEnabledJobs()) {
                                    if (!jobManager.hasJob(player, job.getId()) && job.getId().toLowerCase().startsWith(input)) {
                                        builder.suggest(job.getId());
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            if (!checkRateLimitAndValidate(ctx)) return 0;
                            CommandSender sender = ctx.getSource().getSender();
                            String job = ctx.getArgument("job", String.class);
                            String[] args = {CMD_JOIN, job};
                            return joinLeaveHandler.handleCommand(sender, args) ? 1 : 0;
                        })).build();
    }

    private LiteralCommandNode<CommandSourceStack> buildLeaveCommandLiteral() {
        return Commands.literal(CMD_LEAVE)
                .requires(source -> source.getSender() instanceof Player)
                .then(Commands.argument("job", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String input = builder.getRemaining().toLowerCase();
                            CommandSender sender = ctx.getSource().getSender();
                            if (sender instanceof Player player) {
                                for (String jobId : jobManager.getPlayerJobs(player)) {
                                    if (jobId.toLowerCase().startsWith(input)) {
                                        builder.suggest(jobId);
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            if (!checkRateLimitAndValidate(ctx)) return 0;
                            CommandSender sender = ctx.getSource().getSender();
                            String job = ctx.getArgument("job", String.class);
                            String[] args = {CMD_LEAVE, job};
                            return joinLeaveHandler.handleCommand(sender, args) ? 1 : 0;
                        })).build();
    }

    private LiteralCommandNode<CommandSourceStack> buildInfoCommandLiteral() {
        var infoLiteral = Commands.literal(CMD_INFO)
                .requires(source -> source.getSender() instanceof Player)
                .executes(ctx -> {
                    if (!checkRateLimitAndValidate(ctx)) return 0;
                    CommandSender sender = ctx.getSource().getSender();
                    String[] args = {CMD_INFO};
                    return infoStatsHandler.handleCommand(sender, args) ? 1 : 0;
                });

        infoLiteral.then(Commands.argument("target", StringArgumentType.string())
                .suggests((ctx, builder) -> {
                    String input = builder.getRemaining().toLowerCase();
                    for (fr.ax_dev.universejobs.job.Job job : jobManager.getAllJobs()) {
                        if (job.getId().toLowerCase().startsWith(input)) {
                            builder.suggest(job.getId());
                        }
                    }
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.getName().toLowerCase().startsWith(input)) {
                            builder.suggest(player.getName());
                        }
                    }
                    return builder.buildFuture();
                })
                .executes(ctx -> {
                    if (!checkRateLimitAndValidate(ctx)) return 0;
                    CommandSender sender = ctx.getSource().getSender();
                    String target = ctx.getArgument("target", String.class);
                    String[] args = {CMD_INFO, target};
                    return infoStatsHandler.handleCommand(sender, args) ? 1 : 0;
                }));

        return infoLiteral.build();
    }

    private LiteralCommandNode<CommandSourceStack> buildListCommandLiteral() {
        return Commands.literal(CMD_LIST)
                .requires(source -> source.getSender() instanceof Player)
                .executes(ctx -> {
                    if (!checkRateLimitAndValidate(ctx)) return 0;
                    CommandSender sender = ctx.getSource().getSender();
                    String[] args = {CMD_LIST};
                    return infoStatsHandler.handleCommand(sender, args) ? 1 : 0;
                })
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> buildStatsCommandLiteral() {
        var statsLiteral = Commands.literal(CMD_STATS)
                .requires(source -> source.getSender() instanceof Player)
                .executes(ctx -> {
                    if (!checkRateLimitAndValidate(ctx)) return 0;
                    CommandSender sender = ctx.getSource().getSender();
                    String[] args = {CMD_STATS};
                    return infoStatsHandler.handleCommand(sender, args) ? 1 : 0;
                });

        statsLiteral.then(Commands.argument("player", ArgumentTypes.player())
                .suggests((ctx, builder) -> {
                    String input = builder.getRemaining().toLowerCase();
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.getName().toLowerCase().startsWith(input)) {
                            builder.suggest(player.getName());
                        }
                    }
                    return builder.buildFuture();
                })
                .executes(ctx -> {
                    if (!checkRateLimitAndValidate(ctx)) return 0;
                    CommandSender sender = ctx.getSource().getSender();
                    PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                    List<Player> players = resolver.resolve(ctx.getSource());
                    if (!players.isEmpty()) {
                        String[] args = {CMD_STATS, players.get(0).getName()};
                        return infoStatsHandler.handleCommand(sender, args) ? 1 : 0;
                    }
                    return 0;
                }));

        return statsLiteral.build();
    }

    private LiteralCommandNode<CommandSourceStack> buildRewardsCommandLiteral() {
        var rewardsLiteral = Commands.literal(CMD_REWARDS)
                .requires(source -> source.getSender() instanceof Player && source.getSender().hasPermission("universejobs.rewards.use"))
                .executes(ctx -> {
                    if (!checkRateLimitAndValidate(ctx)) return 0;
                    CommandSender sender = ctx.getSource().getSender();
                    String[] args = {CMD_REWARDS};
                    return rewardsHandler.handleCommand(sender, args) ? 1 : 0;
                });

        // rewards open <job>
        var openLiteral = Commands.literal("open")
                .then(Commands.argument("job", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String input = builder.getRemaining().toLowerCase();
                            CommandSender sender = ctx.getSource().getSender();
                            if (sender instanceof Player player) {
                                for (String jobId : jobManager.getPlayerJobs(player)) {
                                    if (jobId.toLowerCase().startsWith(input)) {
                                        builder.suggest(jobId);
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            if (!checkRateLimitAndValidate(ctx)) return 0;
                            CommandSender sender = ctx.getSource().getSender();
                            String job = ctx.getArgument("job", String.class);
                            String[] args = {CMD_REWARDS, "open", job};
                            return rewardsHandler.handleCommand(sender, args) ? 1 : 0;
                        }));

        // rewards claim <job> <reward>
        var claimLiteral = Commands.literal("claim")
                .then(Commands.argument("job", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String input = builder.getRemaining().toLowerCase();
                            CommandSender sender = ctx.getSource().getSender();
                            if (sender instanceof Player player) {
                                for (String jobId : jobManager.getPlayerJobs(player)) {
                                    if (jobId.toLowerCase().startsWith(input)) {
                                        builder.suggest(jobId);
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("reward", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String input = builder.getRemaining().toLowerCase();
                                    String jobId = ctx.getArgument("job", String.class);
                                    for (fr.ax_dev.universejobs.reward.Reward reward : plugin.getRewardManager().getJobRewards(jobId)) {
                                        if (reward.getId().toLowerCase().startsWith(input)) {
                                            builder.suggest(reward.getId());
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    if (!checkRateLimitAndValidate(ctx)) return 0;
                                    CommandSender sender = ctx.getSource().getSender();
                                    String job = ctx.getArgument("job", String.class);
                                    String reward = ctx.getArgument("reward", String.class);
                                    String[] args = {CMD_REWARDS, "claim", job, reward};
                                    return rewardsHandler.handleCommand(sender, args) ? 1 : 0;
                                })));

        rewardsLiteral.then(openLiteral);
        rewardsLiteral.then(claimLiteral);

        return rewardsLiteral.build();
    }

    private LiteralCommandNode<CommandSourceStack> buildActionLimitCommandLiteral() {
        var actionLimitLiteral = Commands.literal(CMD_ACTION_LIMIT)
                .requires(source -> source.getSender().hasPermission("universejobs.admin.actionlimits"));

        // actionlimit restore
        var restoreLiteral = Commands.literal("restore")
                .then(Commands.argument("player", ArgumentTypes.player())
                        .suggests((ctx, builder) -> {
                            String input = builder.getRemaining().toLowerCase();
                            builder.suggest("*");
                            for (Player player : Bukkit.getOnlinePlayers()) {
                                if (player.getName().toLowerCase().startsWith(input)) {
                                    builder.suggest(player.getName());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("job", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String input = builder.getRemaining().toLowerCase();
                                    builder.suggest("*");
                                    for (fr.ax_dev.universejobs.job.Job job : jobManager.getAllJobs()) {
                                        if (job.getId().toLowerCase().startsWith(input)) {
                                            builder.suggest(job.getId());
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            String input = builder.getRemaining().toLowerCase();
                                            List<String> targets = Arrays.asList("STONE", "DIAMOND_ORE", "COAL_ORE", "IRON_ORE", "WHEAT", "ZOMBIE", "CREEPER", "*");
                                            for (String target : targets) {
                                                if (target.toLowerCase().startsWith(input)) {
                                                    builder.suggest(target);
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            if (!checkRateLimitAndValidate(ctx)) return 0;
                                            CommandSender sender = ctx.getSource().getSender();
                                            PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                                            List<Player> players = resolver.resolve(ctx.getSource());
                                            if (!players.isEmpty()) {
                                                String player = players.get(0).getName();
                                                String job = ctx.getArgument("job", String.class);
                                                String target = ctx.getArgument("target", String.class);
                                                String[] args = {CMD_ACTION_LIMIT, "restore", player, job, target};
                                                return actionLimitHandler.handleCommand(sender, args) ? 1 : 0;
                                            }
                                            return 0;
                                        }))));

        // actionlimit status
        var statusLiteral = Commands.literal("status")
                .then(Commands.argument("player", ArgumentTypes.player())
                        .suggests((ctx, builder) -> {
                            String input = builder.getRemaining().toLowerCase();
                            for (Player player : Bukkit.getOnlinePlayers()) {
                                if (player.getName().toLowerCase().startsWith(input)) {
                                    builder.suggest(player.getName());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("job", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String input = builder.getRemaining().toLowerCase();
                                    for (fr.ax_dev.universejobs.job.Job job : jobManager.getAllJobs()) {
                                        if (job.getId().toLowerCase().startsWith(input)) {
                                            builder.suggest(job.getId());
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            String input = builder.getRemaining().toLowerCase();
                                            List<String> targets = Arrays.asList("STONE", "DIAMOND_ORE", "COAL_ORE", "IRON_ORE", "WHEAT", "ZOMBIE", "CREEPER", "*");
                                            for (String target : targets) {
                                                if (target.toLowerCase().startsWith(input)) {
                                                    builder.suggest(target);
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            if (!checkRateLimitAndValidate(ctx)) return 0;
                                            CommandSender sender = ctx.getSource().getSender();
                                            PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                                            List<Player> players = resolver.resolve(ctx.getSource());
                                            if (!players.isEmpty()) {
                                                String player = players.get(0).getName();
                                                String job = ctx.getArgument("job", String.class);
                                                String target = ctx.getArgument("target", String.class);
                                                String[] args = {CMD_ACTION_LIMIT, "status", player, job, target};
                                                return actionLimitHandler.handleCommand(sender, args) ? 1 : 0;
                                            }
                                            return 0;
                                        }))));

        actionLimitLiteral.then(restoreLiteral);
        actionLimitLiteral.then(statusLiteral);

        return actionLimitLiteral.build();
    }

    private LiteralCommandNode<CommandSourceStack> buildMenuCommandLiteral() {
        var menuLiteral = Commands.literal(CMD_MENU)
                .requires(source -> source.getSender() instanceof Player)
                .executes(ctx -> {
                    if (!checkRateLimitAndValidate(ctx)) return 0;
                    CommandSender sender = ctx.getSource().getSender();
                    String[] args = {CMD_MENU};
                    return menuHandler.handleCommand(sender, args) ? 1 : 0;
                });

        // menu rankings
        menuLiteral.then(Commands.literal("rankings")
                .executes(ctx -> {
                    if (!checkRateLimitAndValidate(ctx)) return 0;
                    CommandSender sender = ctx.getSource().getSender();
                    String[] args = {CMD_MENU, "rankings"};
                    return menuHandler.handleCommand(sender, args) ? 1 : 0;
                }));

        // menu actions <job>
        menuLiteral.then(Commands.literal("actions")
                .then(Commands.argument("job", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String input = builder.getRemaining().toLowerCase();
                            CommandSender sender = ctx.getSource().getSender();
                            for (fr.ax_dev.universejobs.job.Job job : jobManager.getEnabledJobs()) {
                                if (job.getPermission() == null || sender.hasPermission(job.getPermission())) {
                                    if (job.getId().toLowerCase().startsWith(input)) {
                                        builder.suggest(job.getId());
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            if (!checkRateLimitAndValidate(ctx)) return 0;
                            CommandSender sender = ctx.getSource().getSender();
                            String job = ctx.getArgument("job", String.class);
                            String[] args = {CMD_MENU, "actions", job};
                            return menuHandler.handleCommand(sender, args) ? 1 : 0;
                        })));

        // menu reload
        menuLiteral.then(Commands.literal("reload")
                .requires(source -> source.getSender().hasPermission("universejobs.admin.menu.reload"))
                .executes(ctx -> {
                    if (!checkRateLimitAndValidate(ctx)) return 0;
                    CommandSender sender = ctx.getSource().getSender();
                    String[] args = {CMD_MENU, "reload"};
                    return menuHandler.handleCommand(sender, args) ? 1 : 0;
                }));

        // menu <job>
        menuLiteral.then(Commands.argument("job", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    String input = builder.getRemaining().toLowerCase();
                    CommandSender sender = ctx.getSource().getSender();
                    for (fr.ax_dev.universejobs.job.Job job : jobManager.getEnabledJobs()) {
                        if (job.getPermission() == null || sender.hasPermission(job.getPermission())) {
                            if (job.getId().toLowerCase().startsWith(input)) {
                                builder.suggest(job.getId());
                            }
                        }
                    }
                    return builder.buildFuture();
                })
                .executes(ctx -> {
                    if (!checkRateLimitAndValidate(ctx)) return 0;
                    CommandSender sender = ctx.getSource().getSender();
                    String job = ctx.getArgument("job", String.class);
                    String[] args = {CMD_MENU, job};
                    return menuHandler.handleCommand(sender, args) ? 1 : 0;
                }));

        return menuLiteral.build();
    }

    private LiteralCommandNode<CommandSourceStack> buildAdminCommandLiteral() {
        var adminLiteral = Commands.literal(CMD_ADMIN)
                .requires(source -> source.getSender().hasPermission("universejobs.admin"));

        // admin give xp give <player> <job> <amount>
        var giveXpGiveLiteral = Commands.literal("give")
                .then(Commands.argument("player", ArgumentTypes.player())
                        .suggests((ctx, builder) -> {
                            String input = builder.getRemaining().toLowerCase();
                            for (Player player : Bukkit.getOnlinePlayers()) {
                                if (player.getName().toLowerCase().startsWith(input)) {
                                    builder.suggest(player.getName());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("job", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String input = builder.getRemaining().toLowerCase();
                                    for (fr.ax_dev.universejobs.job.Job job : jobManager.getAllJobs()) {
                                        if (job.getId().toLowerCase().startsWith(input)) {
                                            builder.suggest(job.getId());
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            String input = builder.getRemaining().toLowerCase();
                                            List<String> amounts = Arrays.asList("100", "500", "1000", "5000", "10000", "0", "10", "50");
                                            for (String amount : amounts) {
                                                if (amount.startsWith(input)) {
                                                    builder.suggest(amount);
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            if (!checkRateLimitAndValidate(ctx)) return 0;
                                            CommandSender sender = ctx.getSource().getSender();
                                            PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                                            List<Player> players = resolver.resolve(ctx.getSource());
                                            if (!players.isEmpty()) {
                                                String player = players.get(0).getName();
                                                String job = ctx.getArgument("job", String.class);
                                                String amount = ctx.getArgument("amount", String.class);
                                                String[] args = {CMD_ADMIN, "give", "xp", "give", player, job, amount};
                                                return adminJobHandler.handleAdminCommand(sender, args) ? 1 : 0;
                                            }
                                            return 0;
                                        }))));

        var giveXpLiteral = Commands.literal("xp").then(giveXpGiveLiteral);

        // admin give level give <player> <job> <amount>
        var giveLevelGiveLiteral = Commands.literal("give")
                .then(Commands.argument("player", ArgumentTypes.player())
                        .suggests((ctx, builder) -> {
                            String input = builder.getRemaining().toLowerCase();
                            for (Player player : Bukkit.getOnlinePlayers()) {
                                if (player.getName().toLowerCase().startsWith(input)) {
                                    builder.suggest(player.getName());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("job", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String input = builder.getRemaining().toLowerCase();
                                    for (fr.ax_dev.universejobs.job.Job job : jobManager.getAllJobs()) {
                                        if (job.getId().toLowerCase().startsWith(input)) {
                                            builder.suggest(job.getId());
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .then(Commands.argument("amount", StringArgumentType.string())
                                        .suggests((ctx, builder) -> {
                                            String input = builder.getRemaining().toLowerCase();
                                            List<String> levels = Arrays.asList("1", "5", "10", "20", "50", "100");
                                            for (String level : levels) {
                                                if (level.startsWith(input)) {
                                                    builder.suggest(level);
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            if (!checkRateLimitAndValidate(ctx)) return 0;
                                            CommandSender sender = ctx.getSource().getSender();
                                            PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                                            List<Player> players = resolver.resolve(ctx.getSource());
                                            if (!players.isEmpty()) {
                                                String player = players.get(0).getName();
                                                String job = ctx.getArgument("job", String.class);
                                                String amount = ctx.getArgument("amount", String.class);
                                                String[] args = {CMD_ADMIN, "give", "level", "give", player, job, amount};
                                                return adminJobHandler.handleAdminCommand(sender, args) ? 1 : 0;
                                            }
                                            return 0;
                                        }))));

        var giveLevelLiteral = Commands.literal("level").then(giveLevelGiveLiteral);

        // admin give
        var giveLiteral = Commands.literal("give")
                .then(giveXpLiteral)
                .then(giveLevelLiteral);

        // admin forcejoin <player> <job>
        var forceJoinLiteral = Commands.literal("forcejoin")
                .then(Commands.argument("player", ArgumentTypes.player())
                        .suggests((ctx, builder) -> {
                            String input = builder.getRemaining().toLowerCase();
                            for (Player player : Bukkit.getOnlinePlayers()) {
                                if (player.getName().toLowerCase().startsWith(input)) {
                                    builder.suggest(player.getName());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("job", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String input = builder.getRemaining().toLowerCase();
                                    for (fr.ax_dev.universejobs.job.Job job : jobManager.getAllJobs()) {
                                        if (job.getId().toLowerCase().startsWith(input)) {
                                            builder.suggest(job.getId());
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    if (!checkRateLimitAndValidate(ctx)) return 0;
                                    CommandSender sender = ctx.getSource().getSender();
                                    PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                                    List<Player> players = resolver.resolve(ctx.getSource());
                                    if (!players.isEmpty()) {
                                        String player = players.get(0).getName();
                                        String job = ctx.getArgument("job", String.class);
                                        String[] args = {CMD_ADMIN, "forcejoin", player, job};
                                        return adminJobHandler.handleAdminCommand(sender, args) ? 1 : 0;
                                    }
                                    return 0;
                                })));

        // admin forceleave <player> <job>
        var forceLeaveLiteral = Commands.literal("forceleave")
                .then(Commands.argument("player", ArgumentTypes.player())
                        .suggests((ctx, builder) -> {
                            String input = builder.getRemaining().toLowerCase();
                            for (Player player : Bukkit.getOnlinePlayers()) {
                                if (player.getName().toLowerCase().startsWith(input)) {
                                    builder.suggest(player.getName());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("job", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String input = builder.getRemaining().toLowerCase();
                                    for (fr.ax_dev.universejobs.job.Job job : jobManager.getAllJobs()) {
                                        if (job.getId().toLowerCase().startsWith(input)) {
                                            builder.suggest(job.getId());
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    if (!checkRateLimitAndValidate(ctx)) return 0;
                                    CommandSender sender = ctx.getSource().getSender();
                                    PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                                    List<Player> players = resolver.resolve(ctx.getSource());
                                    if (!players.isEmpty()) {
                                        String player = players.get(0).getName();
                                        String job = ctx.getArgument("job", String.class);
                                        String[] args = {CMD_ADMIN, "forceleave", player, job};
                                        return adminJobHandler.handleAdminCommand(sender, args) ? 1 : 0;
                                    }
                                    return 0;
                                })));

        // admin reload
        var reloadLiteral = Commands.literal("reload")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    String[] args = {CMD_ADMIN, "reload"};
                    return adminJobHandler.handleAdminCommand(sender, args) ? 1 : 0;
                });

        adminLiteral.then(giveLiteral);
        adminLiteral.then(forceJoinLiteral);
        adminLiteral.then(forceLeaveLiteral);
        adminLiteral.then(reloadLiteral);

        return adminLiteral.build();
    }

    // ==================== Utility Methods ====================

    private boolean checkRateLimitAndValidate(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();

        if (!validateCommandStructure(ctx)) {
            return false;
        }

        if (sender instanceof Player player) {
            if (!checkRateLimit(player)) {
                return false;
            }
        }

        return true;
    }

    private boolean checkRateLimit(Player player) {
        long currentTime = System.currentTimeMillis();
        Long lastTime = lastCommandTime.get(player.getUniqueId());

        if (lastTime != null && (currentTime - lastTime) < COMMAND_COOLDOWN_MS) {
            return false;
        }

        lastCommandTime.put(player.getUniqueId(), currentTime);
        return true;
    }

    private boolean validateCommandStructure(CommandContext<CommandSourceStack> ctx) {
        String input = ctx.getInput();
        String[] parts = input.split("\\s+");
        if (parts.length > 10) {
            return false;
        }

        for (String arg : parts) {
            if (arg == null || arg.length() > 256) {
                return false;
            }
            if (COMMAND_INJECTION_PATTERN.matcher(arg).find()) {
                return false;
            }
        }
        return true;
    }

    private void sendConsoleHelp(CommandSender sender) {
        for (String line : languageManager.getMessageList("commands.help.console")) {
            sender.sendMessage(line);
        }
    }
}
