import SwiftUI
import AVFAudio
import UniformTypeIdentifiers
import RelayCore
import RelayKit

/// Developer-only lab for the on-device Gemma intake: load the model, run a typed sentence or a short voice clip, and
/// compare Gemma's fields with the rule extractor's and the per-field merge (`FieldMerge.perEval`). Plain English on
/// purpose (not part of the villager flow, so not localised).
struct GemmaLabScreen: View {
    @State private var lab = GemmaLabModel()
    @State private var importing = false

    var body: some View {
        List {
            Section {
                Text(verbatim: lab.modelStatus).font(.footnote).textSelection(.enabled)
                Button { importing = true } label: { Text(verbatim: "Import model file…") }
                Text(verbatim: lab.loaded ? "Model is LOADED (\(lab.backend))" : "Model is unloaded")
                    .font(.footnote).foregroundStyle(lab.loaded ? .green : .secondary)
                Text(verbatim: "Loads on first use; unloads after \(GemmaRunner.idleSeconds) s idle, when the app is backgrounded, or on a memory warning.")
                    .font(.caption).foregroundStyle(.secondary)
                if let why = lab.blockedReason { Text(verbatim: "Would not load now: \(why)").font(.footnote).foregroundStyle(.red) }
                if let load = lab.loadNote { Text(verbatim: load).font(.footnote).foregroundStyle(.secondary) }
                if let busy = lab.busy { HStack { ProgressView(); Text(verbatim: busy).font(.footnote) } }
            } header: { Text(verbatim: "Model (Gemma 4 E2B)") }

            Section {
                TextField("Text request", text: $lab.text, axis: .vertical)
                Button { Task { await lab.runText() } } label: { Text(verbatim: "Run text") }
                    .disabled(lab.busy != nil || lab.text.isEmpty)
                Button { Task { await lab.toggleRecording() } } label: { Text(verbatim: lab.recording ? "Stop and run" : "Record voice") }
                    .disabled(lab.busy != nil && !lab.recording)
            } header: { Text(verbatim: "Try it") }

            if let r = lab.result {
                Section {
                    mono("\(r.run.backend)\(r.run.fellBack ? " (fell back from GPU)" : ""): first token \(r.run.firstTokenMs) ms, total \(r.run.totalMs) ms")
                    if let e = r.run.error { mono("Error: \(e)").foregroundStyle(.red) }
                    mono("Raw: \(r.run.raw.trimmingCharacters(in: .whitespacesAndNewlines))")
                    let p = r.parsed
                    mono("Gemma: " + (p.valid ? "valid" : "INVALID \(p.issues)") + (p.repairs.isEmpty ? "" : " repaired \(p.repairs)"))
                        .foregroundStyle(p.valid ? .green : .red)
                    if p.jsonFound {
                        mono("  \(p.category.name) u\(p.urgency) p\(p.people) \(p.vuln.map(\.name).sorted().joined(separator: "+")) item=\(p.item) lm=\(p.landmark) lang=\(p.lang.tag)")
                    }
                    if !p.transcript.isEmpty { mono("Transcript: \(p.transcript)") }
                    if let d = r.rules { mono("Rules:  \(Self.describe(d))") }
                    if let m = r.merged { mono("Merged: \(Self.describe(m))") }
                } header: { Text(verbatim: "Result") }
            }
        }
        .navigationTitle("Gemma lab")
        .navigationBarTitleDisplayMode(.inline)
        .task { while !Task.isCancelled { await lab.refresh(); try? await Task.sleep(for: .seconds(1)) } }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data]) { res in
            if case .success(let url) = res { Task { await lab.importModel(url) } }
        }
    }

    private func mono(_ s: String) -> some View {
        Text(verbatim: s).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
    }

    private static func describe(_ d: NeedDraft) -> String {
        "\(d.category.name) u\(d.urgency) p\(d.people) \(d.vulnFlags.map(\.name).sorted().joined(separator: "+")) item=\(d.item) lm=\(d.landmark)"
    }
}

struct GemmaLabResult {
    var run: GemmaRun
    var parsed: ParsedNeed
    var rules: NeedDraft?
    var merged: NeedDraft?
}

@MainActor @Observable
final class GemmaLabModel {
    var text = "we are 4 people trapped on the roof, water is rising, near the school"
    var loaded = false
    var modelPresent = false
    var modelStatus = ""
    var loadNote: String?
    var backend = "none"
    var blockedReason: String?
    var busy: String?
    var recording = false
    var result: GemmaLabResult?

    @ObservationIgnored private let runner = GemmaRunner.shared
    @ObservationIgnored private var recorder: AVAudioRecorder?
    @ObservationIgnored private let clipURL = URL.temporaryDirectory.appending(path: "gemma-lab.wav")

    func refresh() async {
        let url = GemmaRunner.modelURL
        let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? nil
        modelPresent = size != nil
        modelStatus = size.map { "\(url.lastPathComponent): \($0 / 1_000_000) MB in this app's Documents" }
            ?? "Missing. Put \(url.lastPathComponent) in this app's Documents (Finder > device > Files, or Import below)."
        loaded = await runner.isLoaded
        backend = await runner.backend
        blockedReason = await runner.blockedReason()
    }

    func importModel(_ source: URL) async {
        busy = "Copying model into the app…"
        let ok = source.startAccessingSecurityScopedResource()
        defer { if ok { source.stopAccessingSecurityScopedResource() } }
        let dest = GemmaRunner.modelURL
        do {
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.copyItem(at: source, to: dest)
            loadNote = "Imported \(source.lastPathComponent)"
        } catch { loadNote = "Import failed: \(error.localizedDescription)" }
        busy = nil
        await refresh()
    }

    func runText() async {
        busy = "Running on text (loads the model first if needed)…"
        let input = text
        finish(await runner.runText(input), rulesText: input)
    }

    func toggleRecording() async {
        if recording {
            recorder?.stop(); recorder = nil; recording = false
            busy = "Running on audio (loads the model first if needed)…"
            finish(await runner.runAudio(wavPath: clipURL.path), rulesText: nil)
            return
        }
        guard await AVAudioApplication.requestRecordPermission() else { loadNote = "Microphone permission denied."; return }
        do {
            try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker])
            try AVAudioSession.sharedInstance().setActive(true)
            let settings: [String: Any] = [
                AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: 16000, AVNumberOfChannelsKey: 1,
                AVLinearPCMBitDepthKey: 16, AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false,
            ]
            let rec = try AVAudioRecorder(url: clipURL, settings: settings)
            guard rec.record(forDuration: 15) else { loadNote = "Could not start recording."; return }
            recorder = rec; recording = true
        } catch { loadNote = "Recording failed: \(error.localizedDescription)" }
    }

    private func finish(_ run: GemmaRun, rulesText: String?) {
        let parsed = run.parsed
        let t = rulesText ?? parsed.transcript
        let rules = t.isEmpty ? nil : RuleExtractor.extract(t, langHint: nil)
        let merged = rules.map { FieldMerge.perEval.merge($0, parsed.modelFields).0 }
        result = GemmaLabResult(run: run, parsed: parsed, rules: rules, merged: merged)
        busy = nil
    }
}
