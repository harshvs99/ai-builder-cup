# Relay: relief messaging when the towers go down

After a cyclone hits the Odisha coast or Leyte, the cell towers fail first, and people's needs go unheard for days. Relay turns every villager's phone into a mesh node:

- **On the device:** a voice note becomes a classified, compressed need of about 100 bytes. This uses Gemma 3 270M or Gemma 4 E2B and works offline. The need hops from phone to phone over Bluetooth and Wi-Fi Direct using Nearby Connections.
- **At the first phone with signal:** a Gemini agent builds the needs map, prioritises it, drafts dispatches for the district control room, translates them, and sends confirmations back through the mesh.

See [PLAN.md](PLAN.md) for the architecture, wire protocol, phased delivery plan and demo runbook.
