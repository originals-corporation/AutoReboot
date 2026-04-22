package oc.mods.autorebootmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class AutoRebootMod implements ModInitializer {
	public static final String MOD_ID = "autorebootmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private boolean rebootPending = false;
	private int rebootTicks = 0;
	private long ticksSinceStart = 0;
	private String lastTriggeredTime = "";
	private final Set<Integer> processedWarnings = new HashSet<>();

	private String currentLang = "en_us";
	private String rebootMode = "Manual";
	private int rebootSeconds = 15;
	private int intervalMinutes = 360;
	private final List<String> scheduleTimes = new ArrayList<>();
	private final List<Integer> warningMinutes = new ArrayList<>();
	private String startupScript = "start.bat";
	private int scriptDelay = 3;

	private final Map<String, JsonObject> allTranslations = new HashMap<>();

	@Override
	public void onInitialize() {
		LOGGER.info("[AutoReboot] Initializing professional reboot system...");
		loadAllConfigs();

		// Запрещаем вход новым игрокам, если идет процесс перезагрузки
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (rebootPending) {
				handler.disconnect(Text.literal(getMsg("kick_reason")));
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(CommandManager.literal("reboot")
					.requires(source -> source.hasPermissionLevel(0))

					// INFO: Статус перезагрузки
					.then(CommandManager.literal("info")
							.executes(context -> {
								context.getSource().sendFeedback(() -> Text.literal(getDetailedInfo()), false);
								return 1;
							})
					)

					// RELOAD: Перезагрузка конфигов с обнулением таймера
					.then(CommandManager.literal("reload")
							.requires(source -> source.hasPermissionLevel(4))
							.executes(context -> {
								loadAllConfigs();
								ticksSinceStart = 0;
								processedWarnings.clear();
								context.getSource().sendFeedback(() -> Text.literal(getMsg("reload_success")), true);
								return 1;
							})
					)

					// STOP / CANCEL: Остановка процесса перезагрузки
					.then(CommandManager.literal("stop")
							.requires(source -> source.hasPermissionLevel(4))
							.executes(context -> {
								if (!rebootPending) {
									context.getSource().sendError(Text.literal(getMsg("no_reboot_to_cancel")));
									return 0;
								}

								// Отменяем выключение и сбрасываем счетчики
								rebootPending = false;
								rebootTicks = 0;
								ticksSinceStart = 0;
								lastTriggeredTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
								processedWarnings.clear();

								// Уведомляем сервер
								context.getSource().getServer().getPlayerManager().broadcast(Text.literal(getMsg("cancel_announcement")), false);
								return 1;
							})
					)

					// MANUAL START: Запуск перезагрузки
					.executes(context -> {
						if (!context.getSource().hasPermissionLevel(4)) {
							context.getSource().sendError(Text.literal(getMsg("no_permission")));
							return 0;
						}
						startRebootProcess(context.getSource().getServer());
						return 1;
					})
			);
		});

		ServerTickEvents.END_SERVER_TICK.register(this::onTick);
	}

	private String getDetailedInfo() {
		StringBuilder sb = new StringBuilder();
		sb.append(getMsg("info_header"));
		sb.append(getMsg("info_mode").replace("%s", rebootMode));

		if (rebootMode.equalsIgnoreCase("Manual")) {
			sb.append(getMsg("info_status_manual"));
		} else if (rebootMode.equalsIgnoreCase("Interval")) {
			sb.append(getMsg("info_interval_val").replace("%s", String.valueOf(intervalMinutes)));
			long remainingSec = (((long) intervalMinutes * 60 * 20) - ticksSinceStart) / 20;
			sb.append(getMsg("info_remaining").replace("%s", formatTime(Math.max(0, remainingSec))));
		} else if (rebootMode.equalsIgnoreCase("Scheduled")) {
			sb.append(getMsg("info_schedule_list").replace("%s", String.join(", ", scheduleTimes)));
			sb.append(getMsg("info_remaining").replace("%s", formatTime(getSecondsUntilNextSchedule())));
		}

		if (rebootPending) {
			sb.append("\n§c§l(!) ").append(getMsg("info_reboot_in_progress"));
		}

		sb.append(getMsg("info_footer"));
		return sb.toString();
	}

	private long getSecondsUntilNextSchedule() {
		LocalTime now = LocalTime.now();
		long minDiff = Long.MAX_VALUE;
		for (String time : scheduleTimes) {
			try {
				LocalTime target = LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm"));
				long diff = Duration.between(now, target).toSeconds();
				// Если diff < 0, значит время уже прошло, добавляем 24 часа. (Строго меньше нуля!)
				if (diff < 0) diff += 86400;
				if (diff < minDiff) minDiff = diff;
			} catch (Exception ignored) {}
		}
		return minDiff == Long.MAX_VALUE ? 0 : minDiff;
	}

	private String formatTime(long seconds) {
		if (seconds <= 0) return "0" + getMsg("time_s");
		long h = seconds / 3600;
		long m = (seconds % 3600) / 60;
		long s = seconds % 60;
		if (h > 0) return h + getMsg("time_h") + " " + m + getMsg("time_m") + " " + s + getMsg("time_s");
		if (m > 0) return m + getMsg("time_m") + " " + s + getMsg("time_s");
		return s + getMsg("time_s");
	}

	private void startRebootProcess(MinecraftServer server) {
		if (rebootPending) return;
		rebootPending = true;
		rebootTicks = rebootSeconds * 20;
		server.saveAll(true, true, true);
		server.getPlayerManager().broadcast(Text.literal(getMsg("server_prefix") + getMsg("start_announcement").replace("%s", String.valueOf(rebootSeconds))), false);
	}

	private String getMsg(String key) {
		JsonObject lang = allTranslations.getOrDefault(currentLang, allTranslations.get("ru_ru"));
		if (lang != null && lang.has(key)) return lang.get(key).getAsString();
		return "§c[" + key + "]";
	}

	private void loadAllConfigs() {
		try {
			Path dirPath = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
			File dir = dirPath.toFile();
			if (!dir.exists()) dir.mkdirs();

			File mainFile = new File(dir, "autorebootmod.json5");
			if (!mainFile.exists()) saveDefaultMainConfig(mainFile);

			try (FileReader fr = new FileReader(mainFile)) {
				JsonReader reader = new JsonReader(fr);
				reader.setLenient(true);
				JsonObject json = GSON.fromJson(reader, JsonObject.class);
				if (json.has("language")) currentLang = json.get("language").getAsString();
				if (json.has("reboot_mode")) rebootMode = json.get("reboot_mode").getAsString();
				if (json.has("reboot_seconds")) rebootSeconds = json.get("reboot_seconds").getAsInt();
				if (json.has("interval_minutes")) intervalMinutes = json.get("interval_minutes").getAsInt();
				if (json.has("startup_script")) startupScript = json.get("startup_script").getAsString();
				if (json.has("script_delay")) scriptDelay = json.get("script_delay").getAsInt();

				scheduleTimes.clear();
				if (json.has("schedule_times")) {
					JsonArray times = json.getAsJsonArray("schedule_times");
					for (int i = 0; i < times.size(); i++) scheduleTimes.add(times.get(i).getAsString());
				}

				warningMinutes.clear();
				if (json.has("reboot_warning_minutes")) {
					JsonArray warns = json.getAsJsonArray("reboot_warning_minutes");
					for (int i = 0; i < warns.size(); i++) warningMinutes.add(warns.get(i).getAsInt());
				}
			}

			ensureLang(dir, "ru_ru", true);
			ensureLang(dir, "en_us", false);

			allTranslations.clear();
			File[] files = dir.listFiles((d, name) -> name.endsWith(".json5") && !name.equals("autorebootmod.json5"));
			if (files != null) {
				for (File f : files) {
					try (FileReader fr = new FileReader(f)) {
						JsonReader jr = new JsonReader(fr);
						jr.setLenient(true);
						allTranslations.put(f.getName().replace(".json5", ""), GSON.fromJson(jr, JsonObject.class));
					}
				}
			}
		} catch (Exception e) { LOGGER.error(e.getMessage()); }
	}

	private void ensureLang(File dir, String name, boolean isRu) throws Exception {
		File file = new File(dir, name + ".json5");
		JsonObject json = file.exists() ? GSON.fromJson(new JsonReader(new FileReader(file)), JsonObject.class) : new JsonObject();
		boolean ch = false;

		if (isRu) {
			ch |= add(json, "warning_generic", "§6§l[!] §eПерезагрузка сервера через §b%s мин§e.");
			ch |= add(json, "start_announcement", "Перезагрузка инициирована! Остановка через §c%s сек!");
			ch |= add(json, "countdown_prefix", "§6[AutoReboot] §eОсталось: §b%s...");
			ch |= add(json, "countdown_subtitle", "§eПриготовьтесь к перезагрузке сервера");
			ch |= add(json, "kick_reason", "§6§lПЕРЕЗАГРУЗКА\n§7Сервер перезагружается. Вы сможете зайти через пару минут.");
			ch |= add(json, "no_permission", "§cОшибка: Недостаточно прав.");
			ch |= add(json, "reload_success", "§a[AutoReboot] Конфигурация успешно перезагружена!");
			ch |= add(json, "cancel_announcement", "§c§l[!] §eПерезагрузка отменена администратором!");
			ch |= add(json, "no_reboot_to_cancel", "§cНет активной перезагрузки для отмены.");
			ch |= add(json, "server_prefix", "§6§l[Сервер] §e");
			ch |= add(json, "info_header", "§8§m--------------------------------------\n§6§lИНФОРМАЦИЯ О ПЕРЕЗАГРУЗКЕ\n");
			ch |= add(json, "info_footer", "\n§8§m--------------------------------------");
			ch |= add(json, "info_mode", "§8» §7Режим: §b%s\n");
			ch |= add(json, "info_status_manual", "§8» §eСтатус: §7Ожидание администратора\n");
			ch |= add(json, "info_remaining", "§8» §eДо начала: §a%s\n");
			ch |= add(json, "info_interval_val", "§8» §7Интервал: §bкаждые %s мин.\n");
			ch |= add(json, "info_schedule_list", "§8» §7График: §b%s\n");
			ch |= add(json, "info_reboot_in_progress", "ПРОЦЕСС ПЕРЕЗАГРУЗКИ ЗАПУЩЕН\n");
			ch |= add(json, "time_h", "ч");
			ch |= add(json, "time_m", "м");
			ch |= add(json, "time_s", "с");
		} else {
			ch |= add(json, "warning_generic", "§6§l[!] §eServer reboot in §b%s min§e.");
			ch |= add(json, "start_announcement", "Reboot initiated! Shutdown in §c%s sec!");
			ch |= add(json, "countdown_prefix", "§6[AutoReboot] §eTime left: §b%s...");
			ch |= add(json, "countdown_subtitle", "§ePrepare for server reboot");
			ch |= add(json, "kick_reason", "§6§lSERVER REBOOT\n§7Server is rebooting. You can join in a few minutes.");
			ch |= add(json, "no_permission", "§cError: Insufficient permissions.");
			ch |= add(json, "reload_success", "§a[AutoReboot] Configuration successfully reloaded!");
			ch |= add(json, "cancel_announcement", "§c§l[!] §eServer reboot cancelled by administrator!");
			ch |= add(json, "no_reboot_to_cancel", "§cNo active reboot to cancel.");
			ch |= add(json, "server_prefix", "§6§l[Server] §e");
			ch |= add(json, "info_header", "§8§m--------------------------------------\n§6§lREBOOT INFORMATION\n");
			ch |= add(json, "info_footer", "\n§8§m--------------------------------------");
			ch |= add(json, "info_mode", "§8» §7Mode: §b%s\n");
			ch |= add(json, "info_status_manual", "§8» §eStatus: §7Waiting for admin\n");
			ch |= add(json, "info_remaining", "§8» §eBefore start: §a%s\n");
			ch |= add(json, "info_interval_val", "§8» §7Interval: §bevery %s min.\n");
			ch |= add(json, "info_schedule_list", "§8» §7Schedule: §b%s\n");
			ch |= add(json, "info_reboot_in_progress", "REBOOT PROCESS STARTED\n");
			ch |= add(json, "time_h", "h");
			ch |= add(json, "time_m", "m");
			ch |= add(json, "time_s", "s");
		}

		if (ch) { try (FileWriter w = new FileWriter(file)) { GSON.toJson(json, w); } }
	}

	private boolean add(JsonObject j, String k, String v) { if (!j.has(k)) { j.addProperty(k, v); return true; } return false; }

	private void saveDefaultMainConfig(File f) throws Exception {
		String content = """
        {
           // The system language for messages (must match a file in the config folder, e.g., "en_us" or "ru_ru").
           "language": "en_us",
           
           // Operation mode: "Manual", "Interval", "Scheduled"
           "reboot_mode": "Manual",
           
           // During the last 5 seconds, large countdown numbers (Titles) will appear on players' screens.
           "reboot_seconds": 15,
           
           // Interval between reboots in minutes (used only if "reboot_mode" is set to "Interval").
           "interval_minutes": 360,
           
           // Specific times for automatic restarts (used only if "reboot_mode" is set to "Scheduled").
           "schedule_times": [
             "00:00",
             "06:00",
             "12:00",
             "18:00"
           ],
           
           // A list of minutes before the reboot to send warning messages to the chat.
           "reboot_warning_minutes": [10, 5, 2, 1],
           
           // The name of the script/file that will restart the server after it stops.
           "startup_script": "start.bat",
           
           // Technical delay in seconds before the server stops and the script launches.
           "script_delay": 3
        }
        """;
		try (FileWriter w = new FileWriter(f)) { w.write(content); }
		loadAllConfigs();
	}

	private void onTick(MinecraftServer server) {
		ticksSinceStart++;

		if (!rebootPending) {
			long remainingTicks = (rebootMode.equalsIgnoreCase("Interval"))
					? ((long) intervalMinutes * 60 * 20) - ticksSinceStart
					: getSecondsUntilNextSchedule() * 20;

			// Идеализированная проверка времени для оповещения без бага:
			// Срабатывает только если осталось ровно кратное 1200 количество тиков (0 секунд).
			if (remainingTicks > 0 && remainingTicks % 1200 == 0) {
				int remMin = (int) (remainingTicks / 1200);
				if (warningMinutes.contains(remMin) && !processedWarnings.contains(remMin)) {
					server.getPlayerManager().broadcast(Text.literal(getMsg("warning_generic").replace("%s", String.valueOf(remMin))), false);
					processedWarnings.add(remMin);
				}
			}

			// Очищаем кэш предупреждений, если таймер сбросился и начал новый большой отсчет
			int currentRemMin = (int) (remainingTicks / 1200);
			if (!warningMinutes.isEmpty() && currentRemMin > Collections.max(warningMinutes)) {
				processedWarnings.clear();
			}

			if (remainingTicks <= 0 && !rebootMode.equalsIgnoreCase("Manual")) {
				String now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
				if (!rebootMode.equalsIgnoreCase("Scheduled") || !now.equals(lastTriggeredTime)) {
					lastTriggeredTime = now;
					startRebootProcess(server);
				}
			}
		} else {
			rebootTicks--;
			if (rebootTicks > 0 && rebootTicks % 20 == 0) {
				int s = rebootTicks / 20;
				if (s <= 5) {
					server.getPlayerManager().getPlayerList().forEach(p -> {
						p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(getMsg("countdown_subtitle"))));
						p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("§c" + s)));
					});
				}
				if (s % 30 == 0 || s == 15 || s <= 10) {
					server.getPlayerManager().broadcast(Text.literal(getMsg("countdown_prefix").replace("%s", String.valueOf(s))), false);
				}
			} else if (rebootTicks == 0) {
				Text reason = Text.literal(getMsg("kick_reason"));
				new ArrayList<>(server.getPlayerManager().getPlayerList()).forEach(p -> p.networkHandler.disconnect(reason));
			} else if (rebootTicks <= -(scriptDelay * 20)) {
				rebootPending = false;
				try {
					File s = new File(startupScript);
					if (s.exists()) {
						if (System.getProperty("os.name").toLowerCase().contains("win")) Runtime.getRuntime().exec("cmd /c start " + startupScript);
						else Runtime.getRuntime().exec("sh ./" + startupScript);
					}
				} catch (Exception ignored) {}
				server.stop(false);
			}
		}
	}
}