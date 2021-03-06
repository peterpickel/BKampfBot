package bkampfbot.utils;

/*
 Copyright (C) 2012  georf@georf.de

 This file is part of BKampfBot.

 BKampfBot is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 2 of the License, or
 any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.util.GregorianCalendar;

import json.JSONException;
import json.JSONObject;
import bkampfbot.Control;
import bkampfbot.Utils;
import bkampfbot.exceptions.BadOpponent;
import bkampfbot.exceptions.FatalError;
import bkampfbot.exceptions.LocationChangedException;
import bkampfbot.exceptions.RestartLater;
import bkampfbot.output.Output;
import bkampfbot.output.TacticsLogFile;
import bkampfbot.plan.PlanObject;
import bkampfbot.state.Config;
import bkampfbot.state.Opponent;
import bkampfbot.state.User;

public class Keilerei {

	// for fighting
	private final Opponent opponent;

	// after fight
	private final AngriffOptions options;

	// for look ahead
	private PlanObject current;

	// for logging
	private final String method;

	public static int fight(Opponent opp, String method,
			AngriffOptions options, PlanObject current) throws BadOpponent,
			FatalError, RestartLater {

		Keilerei k = new Keilerei(opp, method, options, current);

		return k.runFight();

	}

	private Keilerei(Opponent opponent, String method, AngriffOptions options,
			PlanObject current) {

		this.opponent = opponent;

		this.options = options;

		this.current = current;

		this.method = method;
	}

	private int runFight() throws BadOpponent, FatalError, RestartLater {

		int returnValue = -1;
		try {

			// set tactics
			setTactics();

			Utils.visit("fights/fight");
			Utils.visit(opponent.getAttack().substring(1));

			Output.printTab("Kampf mit " + opponent.getName() + " - ",
					Output.INFO);

			Control.sleep(5);

			JSONObject fightData = Utils.getJSON("fights/fightData");
			JSONObject fight = Utils.getJSON(fightData.getString("url")
					.replace("/results/", "/getResults/").substring(1));

			// for logging
			Output.addKampf(fight, fightData, method);

			JSONObject res = fight.getJSONObject("results");
			JSONObject p1 = res.getJSONObject("p1");

			if (res.getBoolean("fightWasWon")) {
				Output.print("gewonnen", 1);

				returnValue = p1.getInt("gold");
			} else {
				Output.print("verloren", 1);
			}

			Output.println(" (" + p1.getInt("gold") + ", hp:" + p1.getInt("hp")
					+ ")", 1);

			// set new values to user model
			User.setLevel(Integer.parseInt(fight.getString("mylevel")));
			User.setCurrentLivePoints(Integer.parseInt(p1.getString("lp")));
			User.setMaxLivePoints(Integer.parseInt(p1.getString("maxLp")));

			int deci = -1;

			int buyCrystal = options.getBuyCrystal();
			int medicine = options.getMedicine();

			if (buyCrystal >= 0
					&& (returnValue < 0 || returnValue >= buyCrystal)) {
				Output.printTab("Gebe Zwerg: ", Output.INFO);
				try {
					Utils.getString("fights/waitFight/buy", "fights/start");
					// not bought
					Output.println("Fehler", Output.INFO);
				} catch (LocationChangedException e) {
					// bought
					Output.println("Erfolgreich", Output.INFO);

					// no medicine need
					medicine = -1;

					// no time needed
					deci = 10;
				}
			}

			if (medicine != -1) {
				int procent = Math.round((User.getCurrentLivePoints() / User
						.getMaxLivePoints()) * 100);

				// Wenn weniger Prozent Leben als angegen, dann Medizin kaufen.
				if (procent < medicine) {
					Utils.buyMedicine();

					// Ende anders berechnen

					String page = Utils.getString("fights/waitFight");
					int offset = page
							.indexOf("left:511px; top:347px; width:166px; height:173px");
					page = page.substring(offset);

					offset = page.indexOf("toTime");
					page = page.substring(offset);

					offset = page.indexOf("\n");
					page = page.substring(0, offset);

					page = page.replaceAll("[^0-9]+", "");

					deci = Integer.parseInt(page) * 10;

				}
			}

			if (deci == -1) {
				JSONObject time = fight.getJSONObject("aTime");
				GregorianCalendar c = new GregorianCalendar(Integer
						.parseInt(time.getString("toYear")), Integer
						.parseInt(time.getString("toMonth")) - 1, Integer
						.parseInt(time.getString("toDay")), Integer
						.parseInt(time.getString("toHour")), Integer
						.parseInt(time.getString("toMinute")), Integer
						.parseInt(time.getString("toSecond")));

				GregorianCalendar now = new GregorianCalendar();
				now.setTime(Config.getDate());
				deci = (int) ((c.getTimeInMillis() - now.getTimeInMillis()) / 100);
			}
			// Add 20 seconds
			deci += 200;

			GregorianCalendar beforeLookAhead = new GregorianCalendar();

			if (current != null) {
				// Lookahead
				deci = current.runNextPlan(deci);
			}

			// restliche Zeit abziehen
			deci = deci
					- Math
							.round((new GregorianCalendar().getTimeInMillis() - beforeLookAhead
									.getTimeInMillis()) / 100);

			Control.sleep(deci, 1);

			Utils.visit("fights/start");

			return returnValue;

		} catch (JSONException e) {
			Output.println("nichts", 1);
			throw new BadOpponent(opponent);
		}
	}

	private void setTactics() throws JSONException {

		// Wenn Taktik erwünscht
		if (Config.getTactics()) {

			if (Config.getFightDebug())
				Output.printTab("Suche nach Taktik für " + opponent.getName()
						+ ": ", Output.DEBUG);

			String[] tactics = TacticsLogFile.getTactics(opponent.getName());

			if (tactics != null) {
				if (Config.getFightDebug())
					Output.println("Erfolgreich", Output.DEBUG);

				Strategie.getRandom().setDefens(tactics).save(true);
				Control.sleep(15);
			} else if (Config.getFightDebug())
				Output.println("Fehlgeschlagen", Output.DEBUG);
		}
	}
}
