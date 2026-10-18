package org.relay.voicespike

/** The prompt contract: one call, ONLY JSON back. Few-shot examples are text-only (audio few-shot is not supported). */
object Prompt {
    val LANGS = listOf("en", "hi", "or", "tl", "war", "ceb")

    val SYSTEM = """
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
""".trim()

    fun audioInstruction(langHint: String): String =
        "The attached audio is a short voice message from a disaster survivor (language hint: $langHint). " +
            "Listen to it, transcribe it, and output ONLY the JSON object."

    fun textInstruction(text: String, langHint: String): String =
        "The following is the transcribed message of a disaster survivor (language hint: $langHint). " +
            "Set \"transcript\" to the text unchanged and output ONLY the JSON object.\nInput: \"$text\""
}
