/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.coxlightcolors;

import com.google.inject.Provides;
import java.awt.Color;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.Varbits;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.util.Text;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginType;
import org.pf4j.Extension;

@Extension
@PluginDescriptor(
	name = "CoX Light Colors",
	description = "Set the colors of the light above the loot chest in Chambers of Xeric",
	tags = {"bosses", "combat", "pve", "raid"},
	type = PluginType.PVM,
	enabledByDefault = false
)
@Slf4j
public class CoxLightColorsPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private CoxLightColorsConfig config;

	private GameObject lightObject;
	private GameObject entranceObject;
	private String uniqueItemReceived;

	private int[] defaultLightFaceColors1;
	private int[] defaultLightFaceColors2;
	private int[] defaultLightFaceColors3;

	private int[] defaultEntranceFaceColors1;
	private int[] defaultEntranceFaceColors2;
	private int[] defaultEntranceFaceColors3;

	private static final String SPECIAL_LOOT_MESSAGE = "Special loot:";
	private static final Pattern SPECIAL_DROP_MESSAGE = Pattern.compile("(.+) - (.+)");
	private boolean waitForSpecialLoot;

	private static final int LIGHT_OBJECT_ID = 28848;
	private static final int OLM_ENTRANCE_ID = 29879;
	private static final int VARBIT_LIGHT_TYPE = 5456;

	private static Integer currentLightType; // Default (null), No Unique (0), Unique (1), Dust (2), Twisted Kit (3)

	@Provides
	CoxLightColorsConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CoxLightColorsConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		updateLightColor();
	}

	@Override
	protected void shutDown() throws Exception
	{
		resetFaceColors();
		uniqueItemReceived = null;
		lightObject = null;
		entranceObject = null;
		waitForSpecialLoot = false;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged varbitChanged)
	{
		updateLightColor();
		if (!isInRaid())
		{
			resetFaceColors();
			uniqueItemReceived = null;
			lightObject = null;
			entranceObject = null;
			waitForSpecialLoot = false;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage chatMessage)
	{
		if (client.getLocalPlayer() == null)
		{
			return;
		}

		if (chatMessage.getType() == ChatMessageType.FRIENDSCHATNOTIFICATION)
		{
			String message = Text.removeTags(chatMessage.getMessage());
			Matcher matcher;

			if (message.startsWith(SPECIAL_LOOT_MESSAGE))
			{
				waitForSpecialLoot = true;
				log.debug("Special loot message encountered");
			}
			if (waitForSpecialLoot)
			{
				matcher = SPECIAL_DROP_MESSAGE.matcher(message);
				if (matcher.find())
				{
					final String dropReceiver = matcher.group(1);
					final String dropName = matcher.group(2);
					log.debug("Special loot: {} received by {}", dropName, dropReceiver);

					if (dropReceiver.equals(client.getLocalPlayer().getName()))
					{
						log.debug("Special loot was received by local player: {}", dropName);
						uniqueItemReceived = dropName;
						if (lightObject != null)
						{
							log.debug("Light object exists. Recoloring it...");
							recolorAllFaces(lightObject.getEntity().getModel(),
								(dropOptainedIsSpecial() ? config.specificUniqueColor() : getNewLightColor()), true);
						}
					}
				}
			}
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		GameObject obj = event.getGameObject();
		if (obj.getId() == LIGHT_OBJECT_ID)
		{
			lightObject = obj;
			updateLightColor();
		}
		else if (obj.getId() == OLM_ENTRANCE_ID)
		{
			entranceObject = obj;
			recolorAllFaces(obj.getEntity().getModel(), config.olmEntrance(), false);
		}
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		if (event.getGameObject().getId() == LIGHT_OBJECT_ID)
		{
			lightObject = null;
		}
		else if (event.getGameObject().getId() == OLM_ENTRANCE_ID)
		{
			entranceObject = null;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (lightObject != null)
		{
			log.debug("Light Object exists on config changed. Unique: {}", (uniqueItemReceived != null ? uniqueItemReceived : "null"));
			recolorAllFaces(lightObject.getEntity().getModel(),
				(dropOptainedIsSpecial() ? config.specificUniqueColor() : getNewLightColor()), true);
		}
		if (entranceObject != null)
		{
			recolorAllFaces(entranceObject.getEntity().getModel(), config.olmEntrance(), false);
		}
	}

	private void updateLightColor()
	{
		if (isInRaid())
		{
			currentLightType = client.getVarbitValue(VARBIT_LIGHT_TYPE);
			if (lightObject != null)
			{
				recolorAllFaces(lightObject.getEntity().getModel(),
					(dropOptainedIsSpecial() ? config.specificUniqueColor() : getNewLightColor()), true);
			}
		}
	}

	private boolean dropOptainedIsSpecial()
	{
		if (uniqueItemReceived == null || uniqueItemReceived.isEmpty())
		{
			return false;
		}
		switch (uniqueItemReceived.toLowerCase().trim())
		{
			case "twisted bow":
				return config.specifyTwistedBow();
			case "kodai insignia":
				return config.specifyKodaiInsignia();
			case "elder maul":
				return config.specifyElderMaul();
			case "dragon claws":
				return config.specifyDragonClaws();
			case "ancestral hat":
				return config.specifyAncestralHat();
			case "ancestral robe top":
				return config.specifyAncestralRobeTop();
			case "ancestral robe bottom":
				return config.specifyAncestralRobeBottom();
			case "dinh's bulwark":
				return config.specifyDinhsBulwark();
			case "dragon hunter crossbow":
				return config.specifyDragonHunterCrossbow();
			case "twisted buckler":
				return config.specifyTwistedBuckler();
			case "arcane prayer scroll":
				return config.specifyArcanePrayerScroll();
			case "dexterous prayer scroll":
				return config.specifyDexPrayerScroll();
			default:
				return false;
		}
	}

	private Color getNewLightColor()
	{
		if (currentLightType == null)
		{
			return null;
		}
		switch (currentLightType)
		{
			case 1:
				return config.noUnique();
			case 2:
				return config.unique();
			case 3:
				return config.dust();
			case 4:
				return config.twistedKit();
			default:
				return null;
		}
	}

	private void recolorAllFaces(Model model, Color color, boolean isLight)
	{
		if (model == null || color == null)
		{
			return;
		}

		int rs2hsb = colorToRs2hsb(color);
		int[] faceColors1 = model.getFaceColors1();
		int[] faceColors2 = model.getFaceColors2();
		int[] faceColors3 = model.getFaceColors3();

		if (isLight && (defaultLightFaceColors1 == null || defaultLightFaceColors1.length == 0))
		{
			defaultLightFaceColors1 = faceColors1.clone();
			defaultLightFaceColors2 = faceColors2.clone();
			defaultLightFaceColors3 = faceColors3.clone();
		}
		else if (defaultEntranceFaceColors1 == null || defaultEntranceFaceColors1.length == 0)
		{
			defaultEntranceFaceColors1 = faceColors1.clone();
			defaultEntranceFaceColors2 = faceColors2.clone();
			defaultEntranceFaceColors3 = faceColors3.clone();
		}
		replaceFaceColorValues(faceColors1, faceColors2, faceColors3, rs2hsb);
	}

	private boolean isInRaid()
	{
		return (client.getGameState() == GameState.LOGGED_IN && client.getVar(Varbits.IN_RAID) == 1);
	}

	private int colorToRs2hsb(Color color)
	{
		float[] hsbVals = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);

		// "Correct" the brightness level to avoid going to white at full saturation, or having a low brightness at
		// low saturation
		hsbVals[2] -= Math.min(hsbVals[1], hsbVals[2] / 2);

		int encode_hue = (int) (hsbVals[0] * 63);
		int encode_saturation = (int) (hsbVals[1] * 7);
		int encode_brightness = (int) (hsbVals[2] * 127);
		return (encode_hue << 10) + (encode_saturation << 7) + (encode_brightness);
	}

	private void resetFaceColors()
	{
		if (lightObject != null && lightObject.getEntity().getModel() != null && defaultLightFaceColors1 != null && defaultLightFaceColors2 != null && defaultLightFaceColors3 != null)
		{
			Model model = lightObject.getEntity().getModel();
			replaceFaceColorValues(model.getFaceColors1(), model.getFaceColors2(), model.getFaceColors3(),
				defaultLightFaceColors1, defaultLightFaceColors2, defaultLightFaceColors3);
			defaultLightFaceColors1 = null;
			defaultLightFaceColors2 = null;
			defaultLightFaceColors3 = null;
		}
		if (entranceObject != null && entranceObject.getEntity().getModel() != null
			&& defaultEntranceFaceColors1 != null && defaultEntranceFaceColors2 != null && defaultEntranceFaceColors3 != null)
		{
			Model model = entranceObject.getEntity().getModel();
			replaceFaceColorValues(model.getFaceColors1(), model.getFaceColors2(), model.getFaceColors3(),
				defaultEntranceFaceColors1, defaultEntranceFaceColors2, defaultEntranceFaceColors3);
			defaultEntranceFaceColors1 = null;
			defaultEntranceFaceColors2 = null;
			defaultEntranceFaceColors3 = null;
		}
	}

	private void replaceFaceColorValues(int[] faceColors1, int[] faceColors2, int[] faceColors3,
										int[] newFaceColors1, int[] newFaceColors2, int[] newFaceColors3)
	{
		if (faceColors1.length == newFaceColors1.length && faceColors2.length == newFaceColors2.length
			&& faceColors3.length == newFaceColors3.length)
		{
			System.arraycopy(newFaceColors1, 0, faceColors1, 0, faceColors1.length);
			System.arraycopy(newFaceColors2, 0, faceColors2, 0, faceColors1.length);
			System.arraycopy(newFaceColors3, 0, faceColors3, 0, faceColors1.length);
		}
	}

	private void replaceFaceColorValues(int[] faceColors1, int[] faceColors2, int[] faceColors3, int globalReplacement)
	{
		if (faceColors1.length > 0)
		{
			Arrays.fill(faceColors1, globalReplacement);
		}
		if (faceColors2.length > 0)
		{
			Arrays.fill(faceColors2, globalReplacement);
		}
		if (faceColors3.length > 0)
		{
			Arrays.fill(faceColors3, globalReplacement);
		}
	}
}
