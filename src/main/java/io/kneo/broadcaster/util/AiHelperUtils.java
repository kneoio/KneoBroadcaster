package io.kneo.broadcaster.util;

import io.kneo.broadcaster.model.ai.AiAgent;
import io.kneo.broadcaster.model.ai.LanguagePreference;
import io.kneo.core.localization.LanguageCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;

public final class AiHelperUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiHelperUtils.class);

    private AiHelperUtils() {
    }

    public static boolean shouldPlayJingle(double talkativity) {
        double jingleProbability = 1.0 - talkativity;
        double randomValue = new Random().nextDouble();
        return randomValue < jingleProbability;
    }

    public static LanguageCode selectLanguageByWeight(AiAgent agent) {
        List<LanguagePreference> preferences = agent.getPreferredLang();
        if (preferences == null || preferences.isEmpty()) {
            LOGGER.warn("Agent '{}' has no language preferences, defaulting to English", agent.getName());
            return LanguageCode.en;
        }

        if (preferences.size() == 1) {
            return preferences.get(0).getCode();
        }

        double totalWeight = preferences.stream()
                .mapToDouble(LanguagePreference::getWeight)
                .sum();

        if (totalWeight <= 0) {
            LOGGER.warn("Agent '{}' has invalid weights (total <= 0), using first language", agent.getName());
            return preferences.get(0).getCode();
        }

        double randomValue = new Random().nextDouble() * totalWeight;
        double cumulativeWeight = 0;
        for (LanguagePreference pref : preferences) {
            cumulativeWeight += pref.getWeight();
            if (randomValue <= cumulativeWeight) {
                return pref.getCode();
            }
        }

        return preferences.get(0).getCode();
    }
}
