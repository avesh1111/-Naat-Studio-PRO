import Foundation
import AVFoundation
import AudioToolbox

/// Naat Studio's experimental low-allocation iOS DSP. The output is a software
/// approximation of DD-8 modes, not a BOSS hardware emulator.
private final class NaatDSP {
    let sampleRate: Double
    private var delayLine: [Float]
    private var reverbLine: [Float]
    private var writePos = 0
    private var reverbPos = 0
    private var lowPass: Float = 0
    private var hpPrev: Float = 0
    private var hpState: Float = 0
    private var phase: Double = 0
    private var reverseLine: [Float] = []
    private var reversePos = 0
    private var loopSamples: [Float] = []
    private var loopIndex = 0
    private var loopState = "stopped"
    var level = 0.60, feedback = 0.25, timeMilliseconds = 380.0, master = 0.15
    var vocalFader = 0.65, instrumentFader = 0.55
    var gateDb = -50.0
    var noiseStrength = 2
    var mode = "WARM"
    var bypass = false
    var hall = false
    var warp = false

    init(rate: Double) {
        sampleRate = rate
        delayLine = [Float](repeating: 0, count: max(2000, Int(rate * 2.5)))
        reverbLine = [Float](repeating: 0, count: max(2000, Int(rate * 0.48)))
        reverseLine = [Float](repeating: 0, count: max(1024, Int(rate * 0.5)))
    }

    func loop(_ action: String) -> String {
        switch action {
        case "clear":
            loopSamples.removeAll(keepingCapacity: true)
            loopIndex = 0
            loopState = "stopped"
            return "LOOP CLEARED"
        case "rec":
            if loopSamples.isEmpty {
                loopState = "recording"
                loopIndex = 0
                return "LOOP RECORDING (40s max)"
            }
            loopState = "overdub"
            return "LOOP OVERDUB"
        case "stop":
            if loopState == "recording" || loopState == "overdub" {
                loopIndex = 0
                loopState = "playing"
                return "LOOP PLAYING"
            } else {
                loopState = "stopped"
                return "LOOP STOPPED"
            }
        default: return "UNKNOWN LOOP ACTION"
        }
    }

    /// Passes instruments directly to the right speaker/recording channel for
    /// stage stereo mode. Only vocal on input L goes through gate/delay/hall.
    func sample(vocal raw: Float, instrument rawInstrument: Float,
                stageStereo: Bool, monitoring: Bool) -> (Float, Float, Float, Float) {
        let rawVocal = min(1, max(-1, raw))
        let instrument = min(1, max(-1, rawInstrument)) * Float(instrumentFader)
        let highPass = rawVocal - hpPrev + hpState * 0.995
        hpPrev = rawVocal
        hpState = highPass
        let threshold = Float(pow(10.0, gateDb / 20.0))
        var vocal = highPass
        let quiet = abs(vocal) < threshold
        if noiseStrength > 0 && quiet {
            vocal *= [1.0, 0.65, 0.4, 0.18][min(3, noiseStrength)]
        }

        let loopsMaximum = Int(sampleRate * 40)
        if loopState == "recording" {
            if loopSamples.count < loopsMaximum {
                loopSamples.append(vocal)
            } else {
                loopState = "playing"
                loopIndex = 0
            }
        } else if (loopState == "playing" || loopState == "overdub") && !loopSamples.isEmpty {
            let old = loopSamples[loopIndex]
            if loopState == "overdub" {loopSamples[loopIndex] = min(1, max(-1, old * 0.7 + vocal * 0.65))}
            vocal += old
            loopIndex = (loopIndex + 1) % loopSamples.count
        }

        let length = delayLine.count
        var delaySamples = max(20, min(length - 1, Int(timeMilliseconds * sampleRate / 1000)))
        if mode == "MOD" {
            phase += 5.0 / sampleRate
            delaySamples = max(20, min(length - 1, delaySamples + Int(sin(phase) * sampleRate * 0.003)))
        }
        if mode == "GLT" {
            delaySamples = max(20, min(length - 1, Int(sampleRate * 0.090)))
        }
        let readPos = (writePos + length - delaySamples) % length
        let delayed = delayLine[readPos]
        let fb = Float(min(0.93, max(0, warp ? 0.91 : feedback)))
        let lowCut: Float
        switch mode {
        case "ANALOG": lowCut = 0.19
        case "TAPE": lowCut = 0.28
        case "WARM": lowCut = 0.12
        default: lowCut = 0.8
        }
        lowPass = lowPass * (1 - lowCut) + delayed * lowCut
        delayLine[writePos] = max(-1, min(1, vocal + lowPass * fb))
        writePos = (writePos + 1) % length

        var wet = (mode == "ANALOG" || mode == "TAPE" || mode == "WARM") ? lowPass : delayed
        if mode == "REVERSE" {
            // Reverse buffered audio: a windowed approximation, not physical DD-8.
            let count = reverseLine.count
            reverseLine[reversePos] = vocal
            wet = reverseLine[(reversePos + count / 2) % count]
            reversePos = (reversePos + 1) % count
        } else if mode == "SHIM" {
            // Simple high octave accent: approximated from delay-buffer reads.
            wet = delayed + 0.27 * delayLine[(readPos + max(1, delaySamples / 2)) % length]
        } else if mode == "WARP" && warp {
            wet = delayed * 1.25
        }

        var processed = bypass ? vocal : (vocal * 0.82 + wet * Float(level))
        if !bypass && (hall || mode == "+RV") {
            let rv = reverbLine[reverbPos]
            reverbLine[reverbPos] = min(1, max(-1, processed * 0.34 + rv * 0.64))
            reverbPos = (reverbPos + 1) % reverbLine.count
            processed = processed * 0.86 + rv * 0.20
        }

        let mic = tanh(processed * Float(vocalFader))
        let dry = tanh(instrument)
        let outLeft: Float = monitoring ? mic * Float(master) : 0
        let outRight: Float = monitoring ? (stageStereo ? dry : mic) * Float(master) : 0
        return (outLeft, outRight, mic, stageStereo ? dry : mic)
    }
}

/// AVAudioEngine input tap -> serial vocal-only DSP -> stereo output player.
/// iPhone stereo interface capture uses IN L = vocal and IN R = dry instruments.
/// Stage output uses OUT L = vocal (DD-8), OUT R = instruments (dry).
/// Scheduling adds buffering compared with a custom AudioUnit render callback:
/// check latency on real hardware before stage use.
final class IOSAudio {
    var event: ((String, String) -> Void)?
    private let work = DispatchQueue(label: "naat.studio.ios.audio", qos: .userInteractive)
    private var engine: AVAudioEngine?
    private var outputPlayer: AVAudioPlayerNode?
    private var processor: NaatDSP?
    private var saveFile: AVAudioFile?
    private var lastRecording: URL?
    private var preview: AVAudioPlayer?
    private var inputFormat: AVAudioFormat?
    private var outputFormat: AVAudioFormat?
    private var rawRecord = false
    private var monitor = false
    private var stageStereo = false
    private var stageMode = false
    private var selectedInput = "default"
    private var generation = 0
    private var meterFrame = 0
    private var pending = (level: 0.6, feedback: 0.25, ms: 380.0,
                           mode: "WARM", gate: -50.0, master: 0.15, bypass: false)
    private var pendingHall = false
    private var pendingVocal = 0.65
    private var pendingInstrument = 0.55
    private var pendingNoise = 2
    private var pendingWarp = false

    func start(input: String, monitor: Bool, stage: Bool,
               stereo: Bool = false, outputID: String = "default") {
        stop()
        self.monitor = monitor
        stageStereo = stereo
        stageMode = stage
        selectedInput = input
        AVAudioSession.sharedInstance().requestRecordPermission { [weak self] allowed in
            DispatchQueue.main.async {
                guard let self = self else { return }
                guard allowed else {
                    self.event?("error", "Allow microphone permission in iPhone Settings.")
                    return
                }
                self.startGranted(outputID: outputID)
            }
        }
    }

    private func startGranted(outputID: String) {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .default,
                                    options: [.allowBluetoothA2DP, .defaultToSpeaker])
            try session.setPreferredSampleRate(48000)
            try session.setPreferredIOBufferDuration(0.008)
            try session.setActive(true)
            let candidates = session.availableInputs ?? []
            let selected: AVAudioSessionPortDescription?
            if stageMode {
                selected = candidates.first(where: { $0.portType == .usbAudio && ($0.uid == selectedInput || selectedInput == "default") })
                guard let usb = selected else {
                    event?("error", "Connect a compatible USB audio interface, then refresh USB devices.")
                    return
                }
                try session.setPreferredInput(usb)
                if stageStereo {
                    if session.maximumInputNumberOfChannels >= 2 {
                        try session.setPreferredInputNumberOfChannels(2)
                    } else {
                        event?("error", "USB interface exposes one input channel to iOS. Two independent capture channels are required.")
                        return
                    }
                }
                guard session.currentRoute.outputs.contains(where: { $0.portType == .usbAudio || $0.portType == .headphones }) else {
                    event?("error", "Select a wired/USB output from iOS route picker before connecting PA.")
                    return
                }
                if outputID != "default" &&
                   !session.currentRoute.outputs.contains(where: { $0.uid == outputID }) {
                    event?("hint", "iOS controls physical audio output. Select the correct route using the route picker.")
                }
            } else {
                selected = candidates.first(where: { $0.portType == .builtInMic })
                if let mic = selected { try session.setPreferredInput(mic) }
            }

            let audio = AVAudioEngine()
            let play = AVAudioPlayerNode()
            let keepInputAlive = AVAudioMixerNode()
            audio.attach(play)
            audio.attach(keepInputAlive)
            let input = audio.inputNode
            let sourceFormat = input.outputFormat(forBus: 0)
            guard sourceFormat.sampleRate > 0,
                  sourceFormat.channelCount >= (stageStereo ? 2 : 1),
                  sourceFormat.floatChannelDataAvailable else {
                event?("error", "iPhone/USB microphone format is incompatible. Stereo Live Mehfil requires two input channels.")
                return
            }
            let outFormat = AVAudioFormat(standardFormatWithSampleRate: sourceFormat.sampleRate, channels: 2)!
            audio.connect(input, to: keepInputAlive, format: sourceFormat)
            audio.connect(keepInputAlive, to: audio.mainMixerNode, format: sourceFormat)
            keepInputAlive.outputVolume = 0
            audio.connect(play, to: audio.mainMixerNode, format: outFormat)
            audio.mainMixerNode.outputVolume = 1.0
            let dsp = NaatDSP(rate: sourceFormat.sampleRate)
            dsp.level = pending.level
            dsp.feedback = pending.feedback
            dsp.timeMilliseconds = pending.ms
            dsp.mode = pending.mode
            dsp.gateDb = pending.gate
            dsp.master = pending.master
            dsp.bypass = pending.bypass
            dsp.hall = pendingHall
            dsp.vocalFader = pendingVocal
            dsp.instrumentFader = pendingInstrument
            dsp.noiseStrength = pendingNoise
            dsp.warp = pendingWarp

            engine = audio
            processor = dsp
            inputFormat = sourceFormat
            outputFormat = outFormat
            outputPlayer = play
            generation += 1
            let token = generation
            // Capture all samples, including dry input 2, before mixing.
            input.installTap(onBus: 0, bufferSize: 512, format: sourceFormat) { [weak self] buffer, _ in
                guard let self = self,
                      let data = buffer.floatChannelData,
                      let captured = AVAudioPCMBuffer(pcmFormat: sourceFormat, frameCapacity: buffer.frameLength) else { return }
                captured.frameLength = buffer.frameLength
                if let copy = captured.floatChannelData {
                    for channel in 0..<Int(sourceFormat.channelCount) {
                        copy[channel].update(from: data[channel], count: Int(buffer.frameLength))
                    }
                }
                self.work.async { [weak self] in
                    self?.processCaptured(captured, generation: token)
                }
            }
            audio.prepare()
            try audio.start()
            play.play()
            event?("status", "MIC CONNECTED • iOS \(Int(sourceFormat.sampleRate)) Hz")
            if stageStereo {
                event?("stage", "STEREO ACTIVE • USB IN L vocal FX, IN R instruments dry. OUT L vocal, OUT R instruments. Keep PA gain low.")
            } else if stageMode {
                event?("stage", "MIC ONLY SOUNDCHECK • USB mic FX → wired mixer. Set PA volume low.")
            }
            event?("latency", "iOS USB latency depends on interface and audio buffering. Bluetooth adds unavoidable delay.")
        } catch {
            event?("error", "iOS audio start error: \(error.localizedDescription)")
            stop()
        }
    }

    private func processCaptured(_ src: AVAudioPCMBuffer, generation token: Int) {
        guard token == generation,
              let format = outputFormat,
              let dsp = processor,
              let input = src.floatChannelData,
              let play = outputPlayer,
              let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: src.frameLength),
              let data = out.floatChannelData else { return }
        let n = Int(src.frameLength)
        out.frameLength = src.frameLength
        var vocalPeak: Float = 0
        var instrumentPeak: Float = 0
        let channels = Int(src.format.channelCount)
        var recordedLeft = [Float](repeating:0,count:n)
        var recordedRight = [Float](repeating:0,count:n)
        for i in 0..<n {
            let sourceVocal = input[0][i]
            let sourceInstrument: Float = stageStereo && channels > 1 ? input[1][i] : 0
            let (left, right, processedMic, processedInstrument) =
                dsp.sample(vocal: sourceVocal, instrument: sourceInstrument,
                           stageStereo: stageStereo, monitoring: monitor)
            data[0][i] = left
            data[1][i] = right
            recordedLeft[i] = processedMic
            recordedRight[i] = stageStereo ? processedInstrument : processedMic
            vocalPeak = max(vocalPeak, abs(processedMic))
            instrumentPeak = max(instrumentPeak, abs(processedInstrument))
        }

        if let recorder = saveFile {
            if rawRecord && !stageStereo {
                // Clean stereo duplicate recording, bypassing DD-8 effects.
                if let raw = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: src.frameLength),
                   let a = raw.floatChannelData {
                    raw.frameLength = src.frameLength
                    for i in 0..<n {
                        a[0][i] = input[0][i]
                        a[1][i] = input[0][i]
                    }
                    do { try recorder.write(from: raw) }
                    catch { event?("error", "Cannot write clean WAV: \(error.localizedDescription)") }
                }
            } else {
                // Record the same effected vocal + dry instruments as stage playback,
                // but independently of the monitoring-volume setting.
                if let recorded = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: src.frameLength),
                   let a = recorded.floatChannelData {
                    recorded.frameLength = src.frameLength
                    for i in 0..<n {
                        a[0][i] = recordedLeft[i]
                        a[1][i] = recordedRight[i]
                    }
                    do { try recorder.write(from: recorded) }
                    catch { event?("error", "Cannot write WAV: \(error.localizedDescription)") }
                }
            }
        }
        if monitor {
            // Scheduled buffers are processed on a serial queue, keeping channel order.
            play.scheduleBuffer(out, completionHandler: nil)
        }

        meterFrame += 1
        if meterFrame % 8 == 0 {
            let v = 20 * log10(Double(max(0.00001, vocalPeak)))
            let i = 20 * log10(Double(max(0.00001, instrumentPeak)))
            event?("meter", String(format:"%.1f", v))
            if stageStereo {
                event?("mixmeters", String(format:"%.1f,%.1f",v,i))
            }
        }
    }

    func configure(level: Double, feedback: Double, milliseconds: Double,
                   mode: String, gate: Double, master: Double, bypass: Bool) {
        pending = (level,feedback,milliseconds,mode,gate,master,bypass)
        work.async { [weak self] in
            self?.processor?.level = level
            self?.processor?.feedback = feedback
            self?.processor?.timeMilliseconds = milliseconds
            self?.processor?.mode = mode
            self?.processor?.gateDb = gate
            self?.processor?.master = master
            self?.processor?.bypass = bypass
        }
    }

    func setHall(_ value: Bool) {
        pendingHall=value
        work.async { [weak self] in self?.processor?.hall=value }
    }
    func setMix(vocal: Double, instrument: Double) {
        pendingVocal=vocal; pendingInstrument=instrument
        work.async { [weak self] in
            self?.processor?.vocalFader=vocal
            self?.processor?.instrumentFader=instrument
        }
    }
    func setNoise(_ strength: Int) {
        pendingNoise=strength
        work.async { [weak self] in self?.processor?.noiseStrength=strength }
        event?("noise", "iOS software vocal gate strength \(strength)/3. This is not hardware noise cancellation.")
    }
    func setWarp(_ enabled: Bool) {
        pendingWarp=enabled
        work.async { [weak self] in self?.processor?.warp=enabled }
    }
    func loop(_ action: String) {
        work.async { [weak self] in
            guard let self=self else{return}
            let result=self.processor?.loop(action) ?? "START MICROPHONE FIRST"
            self.event?("loop",result)
        }
    }
    func record(name: String, raw: Bool) {
        work.async { [weak self] in
            guard let self=self, self.engine?.isRunning == true,
                  let format=self.outputFormat else {
                self?.event?("record","Start microphone first.")
                return
            }
            do {
                let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                    .appendingPathComponent("Recordings",isDirectory:true)
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories:true)
                let cleaned = name.replacingOccurrences(of:"[^A-Za-z0-9_-]",with:"_",options:.regularExpression)
                let safe = cleaned.isEmpty ? "Naat" : String(cleaned.prefix(45))
                let dest = folder.appendingPathComponent("\(safe)_\(Int(Date().timeIntervalSince1970)).wav")
                let settings: [String: Any] = [
                    AVFormatIDKey: Int(kAudioFormatLinearPCM),
                    AVSampleRateKey: format.sampleRate,
                    AVNumberOfChannelsKey: 2,
                    AVLinearPCMBitDepthKey: 16,
                    AVLinearPCMIsFloatKey: false,
                    AVLinearPCMIsBigEndianKey: false,
                    AVLinearPCMIsNonInterleaved: false
                ]
                self.saveFile = try AVAudioFile(forWriting: dest, settings: settings,
                                                commonFormat: .pcmFormatFloat32, interleaved:false)
                self.lastRecording = dest
                self.rawRecord = raw
                self.event?("record", self.stageStereo ? "RECORDING • VOCAL + DRY INSTRUMENTS WAV" : "RECORDING WAV")
            } catch {
                self.event?("error","Cannot start WAV: \(error.localizedDescription)")
            }
        }
    }

    func finish() {
        work.async { [weak self] in
            guard let self=self else {return}
            self.saveFile=nil
            self.event?("record",self.lastRecording == nil ? "NO RECORDING" : "SAVED • Files > On My iPhone > Naat Studio > Recordings")
        }
    }
    func play() {
        guard let url = lastRecording else {event?("record","No WAV recorded yet.");return}
        do {
            preview = try AVAudioPlayer(contentsOf: url)
            preview?.play()
            event?("record","PLAYING LAST WAV")
        } catch {
            event?("error","Cannot play WAV: \(error.localizedDescription)")
        }
    }
    func stop() {
        generation += 1
        engine?.inputNode.removeTap(onBus:0)
        engine?.stop()
        engine = nil
        outputPlayer=nil
        inputFormat=nil
        outputFormat=nil
        work.sync {
            saveFile=nil
            processor=nil
        }
        event?("status","MIC STOPPED")
    }
}

private extension AVAudioFormat {
    /// AVAudioEngine audio input must support noninterleaved Float32 for tap DSP.
    var floatChannelDataAvailable: Bool {
        commonFormat == .pcmFormatFloat32 && !isInterleaved
    }
}
