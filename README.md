# Relay

**The tower fell at 21:40. The needs didn't.**

Relay carries a cry for help across phones with no signal, and brings the answer back.
Built by **Assembly Lines** (Harshvardhan Singh, Manas Choudhary) for the Google Cloud AI Builder Cup 2026, Sustainability & Social Impact track.

- **Live control room:** [relay.harshvs.com](https://relay.harshvs.com)

## The problem

After landfall, help exists. It just can't find the people who need it.

- A requester in Puri, Odisha has no bars. Her mother needs insulin, somewhere near the school road.
- The duty officer in the district control room has a map, forty volunteers and six boats, and no idea which village to send them to first.

| Event | What failed |
|---|---|
| Cyclone Amphan, 2020 | ~7,000 of 14,167 cell tower sites in West Bengal went dark |
| Typhoon Odette, 2021 | 135 Philippine cities and towns lost telecoms |
| Cyclone Fani, 2019 | Puri's officials fell back on police VHF radio |

Sources: The Quint, citing TAIPA (May 2020); Philstar Interaksyon (Dec 2021); Hindustan Times (13 May 2019).

Every lifeline assumes the tower is standing. Calls and SMS need a tower; mesh chat needs a contact. Relay needs neither.

| Channel | Works with towers down | Reaches the control room | Arrives ready to act on | Confirms help is coming |
|---|---|---|---|---|
| Calls and SMS | No | Yes | No | Sometimes |
| Emergency helplines | No | Yes | No | No |
| Satellite phones | Officials only | Yes | No | No |
| Mesh chat apps | Yes | No: contacts only | No: free text | No |
| **Relay** | **Yes** | **Yes, via gateway** | **Yes: a ticket** | **Yes, hops back** |

## How it works

A structured need hops until one phone finds signal; the confirmation rides the same path home.

1. **Requester phone.** No signal. A voice note or a tap; Gemma asks up to 3 follow-up questions, offline.
2. **Relay phones.** BLE mesh, TTL 7, store and forward. Nobody opens an app.
3. **Gateway phone.** Any phone with one bar of signal uploads tickets and carries replies.
4. **Relay cloud.** Cloud Run and ADK agents triage, dispatch, write the sitrep and translate.
5. **Control room.** Live map and priority queue. The duty officer approves in one click.
6. **Back the same hops.** *"Boat leaving the school at 15:40. Keep her warm."*
