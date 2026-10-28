package dev.xygave.temperature;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class TemperaturePlugin extends JavaPlugin implements Listener {
    private static final class State {
        String biome = "";
        double body = 36.6, stewRemaining = 0;
        long wetUntil = 0, stewUntil = 0, stewCooldownUntil = 0, heatUntil = 0;
        int stewSeconds = 0, heatSeconds = 0, reminderSeconds = 0, displaySeconds = 0;
        int warningSeconds = 0, damageSeconds = 0;
        boolean criticalNext = false, ownsSlow = false, ownsFrost = false, ownsWeak = false;
        int slowAmplifier = -1;
    }
    private record SourceResult(double warmth, boolean hot) {}
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, SourceResult> cached = new HashMap<>();
    private int clock = 0;
    private boolean active = true;
    private StateFlag temperatureFlag;
    private boolean worldGuardAvailable;
    private File dataFile;
    private FileConfiguration data;

    @Override public void onLoad() {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) return;
        try {
            var registry = WorldGuard.getInstance().getFlagRegistry();
            try {
                StateFlag flag = new StateFlag("temperature-effects", true);
                registry.register(flag);
                temperatureFlag = flag;
            } catch (FlagConflictException ex) {
                Flag<?> existing = registry.get("temperature-effects");
                if (existing instanceof StateFlag flag) temperatureFlag = flag;
                else getLogger().warning("WorldGuard flag conflict: temperature-effects");
            }
        } catch (Exception ex) {
            getLogger().warning("Cannot register WorldGuard flag: " + ex.getMessage());
        }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        active = getConfig().getBoolean("plugin-enabled", true);
        worldGuardAvailable = Bukkit.getPluginManager().isPluginEnabled("WorldGuard") && temperatureFlag != null;
        dataFile = new File(getDataFolder(), "playerdata.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) initialize(player);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::savePlayerData, 20L * 300, 20L * 300);
        getLogger().info("Temperature 1.2.0 enabled");
    }

    @Override public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            State s = states.get(player.getUniqueId());
            if (s != null) clearOwnEffects(player, s);
        }
        savePlayerData();
        states.clear();
        cached.clear();
    }

    private State initialize(Player player) {
        State s = new State();
        s.biome = biome(player);
        s.body = clamp(data.getDouble("players." + player.getUniqueId() + ".body", normalBody()), minBody(), normalBody());
        if (getConfig().getBoolean("display.show-on-join", true)) s.displaySeconds = seconds("display.duration-seconds", 5);
        states.put(player.getUniqueId(), s);
        return s;
    }

    private void savePlayerData() {
        if (data == null) return;
        for (var entry : states.entrySet()) data.set("players." + entry.getKey() + ".body", entry.getValue().body);
        try { data.save(dataFile); }
        catch (IOException ex) { getLogger().warning("Could not save playerdata.yml: " + ex.getMessage()); }
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { initialize(event.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        State s = states.remove(p.getUniqueId());
        if (s != null) {
            data.set("players." + p.getUniqueId() + ".body", s.body);
            clearOwnEffects(p, s);
            savePlayerData();
        }
        cached.remove(p.getUniqueId());
    }
    @EventHandler public void onDeath(PlayerDeathEvent event) {
        State s = states.get(event.getEntity().getUniqueId());
        if (s != null) { resetState(s); clearOwnEffects(event.getEntity(), s); }
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        State s = states.get(event.getPlayer().getUniqueId());
        if (s != null) resetState(s);
    }
    private void resetState(State s) {
        s.body = normalBody(); s.wetUntil = s.stewUntil = s.stewCooldownUntil = s.heatUntil = 0;
        s.stewRemaining = 0; s.stewSeconds = s.heatSeconds = s.warningSeconds = s.damageSeconds = 0;
    }

    @EventHandler public void onConsume(PlayerItemConsumeEvent event) {
        if (event.isCancelled() || !active) return;
        Player p = event.getPlayer();
        State s = states.get(p.getUniqueId());
        if (s == null) s = initialize(p);
        Material m = event.getItem().getType();
        long now = System.currentTimeMillis();
        if (isStew(m) && getConfig().getBoolean("stew.enabled", true) && now >= s.stewCooldownUntil) {
            s.stewRemaining = Math.max(0, getConfig().getDouble("stew.warmth", 0.8));
            s.stewSeconds = Math.max(1, seconds("stew.warming-seconds", 20));
            s.stewUntil = now + seconds("stew.protection-seconds", 30) * 1000L;
            s.stewCooldownUntil = now + seconds("stew.cooldown-seconds", 60) * 1000L;
        }
        if (m == Material.POTION) {
            s.heatUntil = now + seconds("heat.drink-immunity-seconds", 180) * 1000L;
            s.heatSeconds = 0;
        }
    }
    private boolean isStew(Material m) {
        return m == Material.MUSHROOM_STEW || m == Material.RABBIT_STEW
                || m == Material.BEETROOT_SOUP || m == Material.SUSPICIOUS_STEW;
    }

    private void tick() {
        clock++;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.get(p.getUniqueId());
            if (s == null) s = initialize(p);
            String current = biome(p);
            if (!current.equals(s.biome)) {
                s.biome = current;
                cached.remove(p.getUniqueId());
                if (getConfig().getBoolean("display.show-on-biome-change", true))
                    s.displaySeconds = seconds("display.duration-seconds", 5);
            }
            if (++s.reminderSeconds >= seconds("display.reminder-seconds", 300)) {
                s.reminderSeconds = 0;
                s.displaySeconds = seconds("display.duration-seconds", 5);
            }
            int ambient = temperature(p, current);
            if (s.displaySeconds > 0) {
                p.sendActionBar(Component.text("Температура: " + ambient + "°C | " + pretty(current)));
                s.displaySeconds--;
            }
            if (!active || p.isDead() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) {
                clearOwnEffects(p, s);
                continue;
            }
            boolean allowed = regionAllows(p);
            if (p.isInWater()) {
                s.wetUntil = now + seconds("wetness.duration-seconds", 60) * 1000L;
                s.heatUntil = now + seconds("heat.bathing-immunity-seconds", 30) * 1000L;
                s.heatSeconds = 0;
            }
            int scanEvery = Math.max(1, getConfig().getInt("performance.source-scan-seconds", 2));
            if (clock % scanEvery == 0 || !cached.containsKey(p.getUniqueId()))
                cached.put(p.getUniqueId(), scanSources(p));
            SourceResult source = cached.getOrDefault(p.getUniqueId(), new SourceResult(0, false));
            double effective = ambient + armorProtection(p) + source.warmth();
            boolean underground = getConfig().getBoolean("underground.enabled", true)
                    && p.getWorld().getEnvironment() == World.Environment.NORMAL
                    && p.getLocation().getY() <= getConfig().getDouble("underground.safe-y", -30);
            boolean overworld = p.getWorld().getEnvironment() == World.Environment.NORMAL;
            double comfort = getConfig().getDouble("body.comfortable-air-min", 15);
            double rate = 0;
            if (allowed && overworld && !underground && getConfig().getBoolean("cooling.enabled", true) && effective < comfort) {
                // Calibrate cooling against a reference temperature and time to Slowness I.
                // With reference=-45, time=60, exponent=0.5: at -45C the player
                // cools from 36.6C to 33C in exactly 60 seconds (3.6 / 60 = 0.06C/s).
                double deficit = comfort - effective;
                String model = getConfig().getString("cooling.model", "nonlinear");
                double reference = getConfig().getDouble("cooling.reference-temperature", -45.0);
                double referenceSeconds = Math.max(1.0, getConfig().getDouble("cooling.reference-slowness-seconds", 60.0));
                double slowThreshold = getConfig().getDouble("cold-effects.slowness-1-threshold", 33.0);
                double referenceDeficit = Math.max(0.001, comfort - reference);
                double exponent = model.equalsIgnoreCase("linear") ? 1.0
                        : clamp(getConfig().getDouble("cooling.exponent", 0.5), 0.05, 3.0);
                double referenceRate = Math.max(0, normalBody() - slowThreshold) / referenceSeconds;
                rate = referenceRate * Math.pow(deficit / referenceDeficit, exponent);
                rate = Math.min(Math.max(0, getConfig().getDouble("cooling.max-rate", 0.20)), rate);
                if (getConfig().getBoolean("wetness.enabled", true) && now < s.wetUntil)
                    rate *= Math.max(1, getConfig().getDouble("wetness.cooling-multiplier", 1.5));
                if (now < s.stewUntil)
                    rate *= 1 - clamp(getConfig().getDouble("stew.cooling-reduction", 0.4), 0, 1);
            }
            if (rate > 0) s.body = Math.max(minBody(), s.body - rate);
            else {
                // Recovery is gradual, never instant. A warm source accelerates recovery only in comfort.
                double warmRate = getConfig().getDouble("warming.normal-rate", 0.035);
                if (underground) warmRate = getConfig().getDouble("underground.warming-rate", 0.035);
                else if (source.warmth() > 0 && effective >= comfort)
                    warmRate = getConfig().getDouble("warming.heat-source-rate", 0.143333333);
                s.body = Math.min(normalBody(), s.body + Math.max(0, warmRate));
            }
            if (s.stewSeconds > 0 && s.stewRemaining > 0) {
                double amount = s.stewRemaining / s.stewSeconds;
                s.body = Math.min(normalBody(), s.body + amount);
                s.stewRemaining -= amount;
                s.stewSeconds--;
            }
            // Protected WG regions suppress effects, but do not erase accumulated cold.
            if (!allowed) { clearOwnEffects(p, s); s.warningSeconds = s.damageSeconds = s.heatSeconds = 0; continue; }
            applyColdEffects(p, s);
            handleWarnings(p, s, ambient, effective, rate > 0);
            handleHeat(p, s, source, now, effective);
        }
        cached.keySet().retainAll(states.keySet());
    }

    private void applyColdEffects(Player p, State s) {
        double body = s.body;
        double frost = getConfig().getDouble("cold-effects.frost-threshold", 35);
        double slow1 = getConfig().getDouble("cold-effects.slowness-1-threshold", 33);
        double slow2 = getConfig().getDouble("cold-effects.slowness-2-threshold", 30);
        double damage = getConfig().getDouble("cold-effects.damage-threshold", 32);
        double critical = getConfig().getDouble("cold-effects.critical-threshold", 28);
        if (body <= frost) setFrost(p, s); else clearFrost(p, s);
        int level = body <= slow2 ? getConfig().getInt("cold-effects.slowness-2-level", 2)
                : body <= slow1 ? getConfig().getInt("cold-effects.slowness-1-level", 1) : 0;
        // Heat may also cause slowness; the heat handler decides its final level below.
        if (level > 0) applySlow(p, s, level);
        else if (s.heatSeconds == 0) clearSlow(p, s);
        if (getConfig().getBoolean("cold-effects.weakness.enabled", false)
                && body <= getConfig().getDouble("cold-effects.weakness.threshold", 28))
            applyWeakness(p, s, getConfig().getInt("cold-effects.weakness.level", 1));
        else clearWeakness(p, s);
        if (body <= damage) {
            s.damageSeconds++;
            boolean severe = body <= critical;
            double multiplier = severe ? getConfig().getDouble("cold-effects.critical-damage-multiplier", 2) : 1;
            double frequency = severe ? getConfig().getDouble("cold-effects.critical-frequency-multiplier", 2) : 1;
            int interval = Math.max(1, (int) Math.round(seconds("cold-effects.damage-every-seconds", 5) / Math.max(1, frequency)));
            if (s.damageSeconds >= interval) {
                s.damageSeconds = 0;
                double amount = Math.max(0, getConfig().getDouble("cold-effects.damage-amount", 1)) * multiplier;
                if (amount > 0) p.damage(amount);
            }
        } else s.damageSeconds = 0;
    }

    private void handleWarnings(Player p, State s, int ambient, double effective, boolean cooling) {
        boolean critical = s.body <= getConfig().getDouble("cold-effects.critical-threshold", 28);
        boolean coldEnabled = getConfig().getBoolean("display.cold-warning.enabled", true);
        boolean criticalEnabled = getConfig().getBoolean("display.critical-warning.enabled", true);
        if (!cooling && !critical) { s.warningSeconds = 0; s.criticalNext = false; return; }
        if (++s.warningSeconds < seconds("display.warning-interval-seconds", 15)) return;
        s.warningSeconds = 0;
        boolean showCritical = critical && criticalEnabled && (!coldEnabled || s.criticalNext || !cooling);
        String path = showCritical ? "display.critical-warning" : "display.cold-warning";
        if (showCritical || (coldEnabled && cooling)) {
            String fallback = showCritical ? "§cКритическое переохлаждение! {body_temp}°C" : "§bВы начинаете замерзать! {biome_temp}°C";
            String msg = getConfig().getString(path + ".text", fallback);
            msg = msg.replace("{body_temp}", fmt(s.body)).replace("{biome_temp}", String.valueOf(ambient))
                    .replace("{effective_temp}", fmt(effective)).replace("{biome}", pretty(s.biome)).replace('&', '§');
            // Action bar lasts while repeated; duration is controlled by message repetitions below.
            int duration = Math.max(1, getConfig().getInt(path + ".duration-seconds", 3));
            showActionBarFor(p, msg, duration);
        }
        if (critical && coldEnabled && criticalEnabled && cooling) s.criticalNext = !s.criticalNext;
    }

    private void showActionBarFor(Player p, String msg, int duration) {
        // One immediate message and short repeats to approximate the requested duration.
        p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(msg));
        UUID id = p.getUniqueId();
        for (int i = 1; i < duration; i++) {
            int delay = i;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline() && active && states.containsKey(id) && regionAllows(p))
                    p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(msg));
            }, 20L * delay);
        }
    }

    private void handleHeat(Player p, State s, SourceResult source, long now, double effective) {
        boolean nether = p.getWorld().getEnvironment() == World.Environment.NETHER;
        String type = getConfig().getString("biomes." + s.biome + ".type", "normal");
        boolean hotBiome = p.getWorld().getEnvironment() == World.Environment.NORMAL
                && type.equalsIgnoreCase("hot") && !isNight(p.getWorld());
        boolean hotSource = p.getWorld().getEnvironment() == World.Environment.NORMAL && source.hot();
        // Do not punish a hypothermic player for warming up near fire or lava.
        boolean hypothermic = s.body <= getConfig().getDouble("cold-effects.slowness-1-threshold", 33);
        if ((nether || hotBiome || hotSource) && now >= s.heatUntil && !hypothermic) {
            s.heatSeconds++;
            int delay = nether ? seconds("heat.nether-delay-seconds", 60)
                    : hotSource ? seconds("heat.source-delay-seconds", 180) : seconds("heat.desert-delay-seconds", 180);
            if (s.heatSeconds >= delay) applySlow(p, s, 1);
        } else {
            s.heatSeconds = 0;
            if (s.body > getConfig().getDouble("cold-effects.slowness-1-threshold", 33)) clearSlow(p, s);
        }
    }

    private SourceResult scanSources(Player p) {
        int maxRadius = 0;
        for (String name : new String[]{"torch", "campfire", "fire", "lava"})
            maxRadius = Math.max(maxRadius, radius(name));
        int heatRadius = Math.max(0, Math.min(8, getConfig().getInt("heat.source-radius", 3)));
        maxRadius = Math.max(maxRadius, heatRadius);
        Block origin = p.getLocation().getBlock();
        double best = 0;
        boolean hot = false;
        for (int x = -maxRadius; x <= maxRadius; x++)
            for (int y = -maxRadius; y <= maxRadius; y++)
                for (int z = -maxRadius; z <= maxRadius; z++) {
                    int d2 = x*x + y*y + z*z;
                    if (d2 > maxRadius * maxRadius) continue;
                    int bx = origin.getX() + x, bz = origin.getZ() + z;
                    if (!origin.getWorld().isChunkLoaded(bx >> 4, bz >> 4)) continue;
                    int by = origin.getY() + y;
                    if (by < origin.getWorld().getMinHeight() || by >= origin.getWorld().getMaxHeight()) continue;
                    Block b = origin.getRelative(x, y, z);
                    Material m = b.getType();
                    String name = switch (m) {
                        case TORCH, WALL_TORCH, SOUL_TORCH, SOUL_WALL_TORCH -> "torch";
                        case CAMPFIRE, SOUL_CAMPFIRE -> "campfire";
                        case FIRE, SOUL_FIRE -> "fire";
                        case LAVA -> "lava";
                        default -> "";
                    };
                    if (name.isEmpty()) continue;
                    if (name.equals("campfire") && b.getBlockData() instanceof Lightable lightable && !lightable.isLit()) continue;
                    if (d2 <= radius(name) * radius(name))
                        best = Math.max(best, getConfig().getDouble("heat-sources." + name + ".warmth", 0));
                    if ((name.equals("lava") || name.equals("fire")) && d2 <= heatRadius * heatRadius) hot = true;
                }
        return new SourceResult(best, hot);
    }
    private int radius(String name) {
        int fallback = switch (name) { case "torch" -> 2; case "campfire" -> 5; case "fire" -> 3; default -> 4; };
        return Math.max(0, Math.min(8, getConfig().getInt("heat-sources." + name + ".radius", fallback)));
    }
    private boolean regionAllows(Player p) {
        if (!worldGuardAvailable || !getConfig().getBoolean("worldguard.enabled", true)) return true;
        try {
            RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            return query.testState(BukkitAdapter.adapt(p.getLocation()), WorldGuardPlugin.inst().wrapPlayer(p), temperatureFlag);
        } catch (Exception ex) { return true; }
    }
    private void setFrost(Player p, State s) {
        if (p.getLocation().getBlock().getType() == Material.POWDER_SNOW) return;
        int max = p.getMaxFreezeTicks();
        if (max <= 30) return;
        double strength = clamp(getConfig().getDouble("cold-effects.frost-screen-strength", 0.75), 0.1, 0.9);
        int target = Math.max(1, Math.min(max - 25, (int) (max * strength)));
        if (p.getFreezeTicks() < target) { p.setFreezeTicks(target); s.ownsFrost = true; }
    }
    private void clearFrost(Player p, State s) {
        if (s.ownsFrost && p.getLocation().getBlock().getType() != Material.POWDER_SNOW) p.setFreezeTicks(0);
        s.ownsFrost = false;
    }
    private void applySlow(Player p, State s, int level) {
        if (level <= 0) { clearSlow(p, s); return; }
        int amplifier = level - 1;
        PotionEffect existing = p.getPotionEffect(PotionEffectType.SLOWNESS);
        if (existing != null && !s.ownsSlow && (existing.getAmplifier() >= amplifier || existing.getDuration() > 60)) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, amplifier, true, false, false));
        s.ownsSlow = true; s.slowAmplifier = amplifier;
    }
    private void clearSlow(Player p, State s) {
        if (s.ownsSlow) {
            PotionEffect effect = p.getPotionEffect(PotionEffectType.SLOWNESS);
            if (effect != null && effect.getAmplifier() == s.slowAmplifier && effect.getDuration() <= 45)
                p.removePotionEffect(PotionEffectType.SLOWNESS);
        }
        s.ownsSlow = false; s.slowAmplifier = -1;
    }
    private void applyWeakness(Player p, State s, int level) {
        if (level <= 0) return;
        PotionEffect existing = p.getPotionEffect(PotionEffectType.WEAKNESS);
        if (existing != null && existing.getDuration() > 60) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 45, level - 1, true, false, false));
        s.ownsWeak = true;
    }
    private void clearWeakness(Player p, State s) {
        PotionEffect existing = p.getPotionEffect(PotionEffectType.WEAKNESS);
        if (s.ownsWeak && existing != null && existing.getDuration() <= 45 && !existing.hasParticles())
            p.removePotionEffect(PotionEffectType.WEAKNESS);
        s.ownsWeak = false;
    }
    private void clearOwnEffects(Player p, State s) { clearFrost(p, s); clearSlow(p, s); clearWeakness(p, s); }

    private int armorProtection(Player p) {
        int sum = 0;
        for (ItemStack item : p.getInventory().getArmorContents()) {
            if (item == null) continue;
            String name = item.getType().name();
            if (name.startsWith("LEATHER_")) sum += getConfig().getInt("armor.leather-degrees-per-piece", 12);
            else if (name.startsWith("DIAMOND_")) sum += getConfig().getInt("armor.diamond-degrees-per-piece", 2);
            else if (name.startsWith("NETHERITE_")) sum += getConfig().getInt("armor.netherite-degrees-per-piece", 4);
        }
        return sum;
    }
    private String biome(Player p) { return p.getLocation().getBlock().getBiome().getKey().getKey().toUpperCase(Locale.ROOT); }
    private boolean isNight(World w) { return w.getTime() >= 12000 && w.getTime() < 24000; }
    private int temperature(Player p, String biome) {
        if (p.getWorld().getEnvironment() == World.Environment.NETHER) return getConfig().getInt("biomes.nether-temperature", 55);
        if (p.getWorld().getEnvironment() == World.Environment.THE_END) return getConfig().getInt("biomes.end-temperature", 15);
        String time = isNight(p.getWorld()) ? "night" : "day";
        return getConfig().getInt("biomes." + biome + "." + time,
                getConfig().getInt("biomes.default-" + time + "-temperature", 20));
    }
    private String pretty(String name) { return name.toLowerCase(Locale.ROOT).replace('_', ' '); }
    private double normalBody() { return getConfig().getDouble("body.normal-temperature", 36.6); }
    private double minBody() { return Math.min(normalBody(), getConfig().getDouble("body.minimum-temperature", 25)); }
    private double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }
    private int seconds(String path, int fallback) { return Math.max(1, getConfig().getInt(path, fallback)); }
    private String fmt(double value) { return String.format(Locale.ROOT, "%.1f", value); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("reload") || sub.equals("on") || sub.equals("off")) {
                if (!sender.hasPermission("temperature.admin")) { sender.sendMessage("§cНет прав."); return true; }
                if (sub.equals("reload")) {
                    reloadConfig(); active = getConfig().getBoolean("plugin-enabled", true); cached.clear();
                    sender.sendMessage("§aTemperature: настройки перезагружены.");
                } else {
                    active = sub.equals("on");
                    getConfig().set("plugin-enabled", active); saveConfig();
                    if (!active) for (Player p : Bukkit.getOnlinePlayers()) {
                        State s = states.get(p.getUniqueId());
                        if (s != null) clearOwnEffects(p, s);
                    }
                    sender.sendMessage(active ? "§aTemperature: включено." : "§eTemperature: выключено.");
                }
                return true;
            }
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Использование: /temperature [on|off|reload]"); return true; }
        State s = states.get(p.getUniqueId());
        int ambient = temperature(p, biome(p));
        int armor = armorProtection(p);
        double source = cached.getOrDefault(p.getUniqueId(), new SourceResult(0, false)).warmth();
        sender.sendMessage("§bБиом: §f" + biome(p) + " §bТемпература: §f" + ambient + "°C");
        sender.sendMessage("§bБроня: §f+" + armor + "°C §bИсточник тепла: §f+" + fmt(source) + "°C");
        sender.sendMessage("§bЭффективная: §f" + fmt(ambient + armor + source) + "°C §bТело: §f" + fmt(s == null ? normalBody() : s.body) + "°C");
        sender.sendMessage(active ? "§aТемпературные эффекты включены" : "§eТемпературные эффекты выключены");
        return true;
    }
}
