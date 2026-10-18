import Foundation

/// The prompt contract for the on-device Gemma intake: one call, ONLY JSON back. A line-for-line port of
/// `ml/voice-spike/.../Prompt.kt`; change the two together so the iOS and Android evals stay comparable.
public enum GemmaPrompt {
    /// Gemma 4 E2B (audio + text).
    public static let system = #"""
You convert a disaster survivor's short help request into ONE JSON object. Output ONLY the JSON object: no prose, no markdown, no code fences.

Schema (ALL nine keys must always be present, even when a value is empty):
{"transcript": "<verbatim words of the request, in the original language and script>",
 "lang": "en|hi|or|tl|war|ceb",
 "category": "MEDICAL|RESCUE|WATER|FOOD|SHELTER|MISSING_PERSON|OTHER",
 "urgency": 0-3,
 "people": <integer headcount of the people who need help>,
 "vuln": [subset of "ELDERLY","CHILD","PREGNANT","INJURED","TRAPPED"],
 "item": "<=16 bytes, English, one bare noun for what is needed",
 "landmark": "<=24 bytes, English, bare place noun, or empty",
 "summary": "<=40 bytes, English"}

Rules:
- category is what the speaker asks for or is missing, decided by meaning even when no keyword is said: people buried, stranded or unable to get out = RESCUE; pain, bleeding, fever, labour = MEDICAL. Things the speaker already has do not count, only what is lacking.
- urgency: 3 = life-threatening now (trapped, drowning, severe injury or bleeding, labour, cannot breathe), 2 = urgent medical need, or any other need where children, elderly or pregnant people are affected or the speaker asks to hurry, 1 = needs help soon (food, water, shelter, other), 0 = information only.
- people: the headcount the speaker states or clearly means ("my mother" = 1, "the three of us" = 3). If no count can be worked out, use 0. Never guess 1 by default.
- vuln: infer from the words. children, kids, baby, small boy or girl, a child's age under 15, बच्चे = CHILD; old, elderly, grandfather, grandmother, बूढ़े, बुजुर्ग = ELDERLY; pregnant, in labour, गर्भवती = PREGNANT; injured, bleeding, broken bone, चोट = INJURED; trapped, stuck, buried, फँसे, दबे = TRAPPED. Use [] when no word supports a flag.
- item: ONE bare English noun, no adjectives. For rescue, water, food and shelter needs use exactly that word; for a missing person use "missing person"; for medical use the specific thing asked for (e.g. insulin, medicine, bandage); for OTHER the thing asked for.
- landmark: only the bare noun of the place, without near, behind, at, by, the, a, nor the Hindi के पास or के पीछे. Translate Hindi or Hinglish place names into English (नदी = river, दुकान = shop). When no place is named write "landmark":"" - never omit the key.
- lang is the language spoken: en English, hi Hindi (also Hinglish written in Latin script), or Odia, tl Tagalog, war Waray, ceb Cebuano. item, landmark and summary are always in English, even when the speaker is not.

Examples (text input -> output):
Input: "we are 4 people trapped on the roof, the water is rising, near the clock tower"
Output: {"transcript":"we are 4 people trapped on the roof, the water is rising, near the clock tower","lang":"en","category":"RESCUE","urgency":3,"people":4,"vuln":["TRAPPED"],"item":"rescue","landmark":"clock tower","summary":"4 trapped on roof, water rising"}
Input: "my 2 year old has a high fever and we have no medicine, we are by the ration shop"
Output: {"transcript":"my 2 year old has a high fever and we have no medicine, we are by the ration shop","lang":"en","category":"MEDICAL","urgency":2,"people":1,"vuln":["CHILD"],"item":"medicine","landmark":"ration shop","summary":"child with high fever, no medicine"}
Input: "हमारे पास पीने का पानी नहीं है, हम दस लोग हैं, नदी के पास"
Output: {"transcript":"हमारे पास पीने का पानी नहीं है, हम दस लोग हैं, नदी के पास","lang":"hi","category":"WATER","urgency":1,"people":10,"vuln":[],"item":"water","landmark":"river","summary":"10 people, no drinking water"}
Input: "no blankets and it is very cold, there are old people with us"
Output: {"transcript":"no blankets and it is very cold, there are old people with us","lang":"en","category":"OTHER","urgency":2,"people":0,"vuln":["ELDERLY"],"item":"blanket","landmark":"","summary":"no blankets, cold, elderly present"}
"""#

    /// Gemma 3 270M (text only): short instruction, category enum spelled out, two worked examples.
    public static let systemSmall = #"""
Turn a disaster survivor's help request into ONE JSON object. Output only the JSON, nothing else.
Keys, all always present: {"transcript":"<the text unchanged>","lang":"en|hi|or","category":"<one of the 7 below>","urgency":0-3,"people":<integer>,"vuln":[],"item":"<one English noun>","landmark":"<English place noun or empty>","summary":"<short English>"}
category must be exactly one of:
MEDICAL (pain, bleeding, fever, labour, medicine) | RESCUE (trapped, stranded, cannot get out) | WATER (drinking water) | FOOD | SHELTER (roof, tent, blankets) | MISSING_PERSON | OTHER
urgency: 3 life-threatening now, 2 urgent medical or children/elderly/pregnant affected, 1 needs help soon, 0 information only.
people: headcount said or meant, 0 if unknown. vuln: any of ELDERLY, CHILD, PREGNANT, INJURED, TRAPPED, else [].
item: for RESCUE, WATER, FOOD, SHELTER use that word; for MEDICAL the thing asked for (medicine, insulin). landmark without near/behind/by.
Examples:
Input: "amey chaari jana chhata upare phasi achhu, pani badhuchi, school pakhare"
Output: {"transcript":"amey chaari jana chhata upare phasi achhu, pani badhuchi, school pakhare","lang":"or","category":"RESCUE","urgency":3,"people":4,"vuln":["TRAPPED"],"item":"rescue","landmark":"school","summary":"4 trapped on roof, water rising"}
Input: "ମୋ ମା'ଙ୍କୁ ଔଷଧ ଦରକାର, ସେ ଚାଲି ପାରୁନାହାନ୍ତି, ଆମେ ସ୍କୁଲ ପାଖରେ ଅଛୁ"
Output: {"transcript":"ମୋ ମା'ଙ୍କୁ ଔଷଧ ଦରକାର, ସେ ଚାଲି ପାରୁନାହାନ୍ତି, ଆମେ ସ୍କୁଲ ପାଖରେ ଅଛୁ","lang":"or","category":"MEDICAL","urgency":2,"people":1,"vuln":[],"item":"medicine","landmark":"school","summary":"mother needs medicine, cannot walk"}
"""#

    /// Ends with "Output:" so a small model continues straight into the JSON.
    public static func textInstructionSmall(_ text: String) -> String { "Input: \"\(text)\"\nOutput:" }

    public static func audioInstruction(langHint: String) -> String {
        "The attached audio is a short voice message from a disaster survivor (language hint: \(langHint)). "
            + "Listen to it, transcribe it, and output ONLY the JSON object."
    }

    public static func textInstruction(_ text: String, langHint: String) -> String {
        "The following is the transcribed message of a disaster survivor (language hint: \(langHint)). "
            + "Set \"transcript\" to the text unchanged and output ONLY the JSON object.\nInput: \"\(text)\""
    }
}
