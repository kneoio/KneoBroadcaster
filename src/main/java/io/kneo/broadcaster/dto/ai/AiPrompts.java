package io.kneo.broadcaster.dto.ai;

public class AiPrompts {

    public static final String DECISION_PROMPT =
            "INPUTS:\n" +
                    "- Events: {events}\n" +
                    "- Context: {context}\n" +
                    "- Available ads: {ads}\n" +
                    "\n" +
                    "ANALYSIS:\n" +
                    "1. Check for ad events in {events}\n" +
                    "2. IF ad events present:\n" +
                    "   - Set action: \"ad\"\n" +
                    "   - Select randomly one ad UUID from {ads}\n" +
                    "   - Generate transition: \"Sorry to interrupt, quick message\" OR \"Word from our sponsors\" OR \"Something interesting coming up\"\n" +
                    "3. IF no ad events:\n" +
                    "   - Set action: \"song\"\n" +
                    "   - Analyze {events} and {context} to determine mood from: aggressive, calm, chilled, dark, energetic, epic, happy, romantic, sad, scary, sexy, ethereal, uplifting, feel_good, bouncy, dreamy, melancholic, mysterious, peaceful, intense, playful, nostalgic, triumphant";

}