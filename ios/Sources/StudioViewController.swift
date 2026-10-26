import UIKit
import WebKit
import AVFoundation
import AVKit

final class StudioViewController: UIViewController, WKScriptMessageHandler {
    private var web: WKWebView!
    private let audio = IOSAudio()
    private let picker = AVRoutePickerView(frame: .zero)
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.025, green: 0.08, blue: 0.16, alpha: 1)
        let user = WKUserContentController()
        user.add(self, name: "naat")
        let saved = UserDefaults.standard.string(forKey: "presets") ?? ""
        let literal = json(saved)
        let bridge = """
        (function(){
          var p = \(literal);
          window.__iosMics = [{id:'default',kind:'phone',name:'iPhone Microphone',channelCounts:'1'}];
          window.__iosOutputs = [{id:'default',kind:'default',name:'iOS Current Output'}];
          const send=(m,a)=>window.webkit.messageHandlers.naat.postMessage({method:m,args:a||[]});
          window.NativeApp={
            getPresets:()=>p,savePresets:x=>{p=x;send('savePresets',[x])},
            microphones:()=>JSON.stringify(window.__iosMics),outputs:()=>JSON.stringify(window.__iosOutputs),
            start:(...a)=>send('start',a),startMehfil:(...a)=>send('stage',a),
            startBluetoothLive:(...a)=>send('bt',a),stop:()=>send('stop'),
            params:(...a)=>send('params',a),hall:(...a)=>send('hall',a),
            noiseReduction:(...a)=>send('noise',a),fastLive:(...a)=>send('fast',a),
            mix:(...a)=>send('mix',a),requestBluetooth:()=>send('pick',[]),
            recordStart:(...a)=>send('record',a),recordStop:()=>send('recordStop'),
            playLast:()=>send('play'),warp:()=>send('warp'),loop:(...a)=>send('loop',a)
          };
        })();
        """
        user.addUserScript(WKUserScript(source: bridge, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let config = WKWebViewConfiguration(); config.userContentController = user
        web = WKWebView(frame: .zero, configuration: config)
        web.isOpaque=false; web.backgroundColor=view.backgroundColor
        web.scrollView.contentInsetAdjustmentBehavior = .never
        view.addSubview(web); web.translatesAutoresizingMaskIntoConstraints=false
        NSLayoutConstraint.activate([
            web.leadingAnchor.constraint(equalTo:view.leadingAnchor),web.trailingAnchor.constraint(equalTo:view.trailingAnchor),
            web.topAnchor.constraint(equalTo:view.safeAreaLayoutGuide.topAnchor),web.bottomAnchor.constraint(equalTo:view.bottomAnchor)
        ])
        picker.tintColor = .cyan; picker.translatesAutoresizingMaskIntoConstraints=false
        view.addSubview(picker)
        NSLayoutConstraint.activate([
            picker.topAnchor.constraint(equalTo:view.safeAreaLayoutGuide.topAnchor,constant:4),
            picker.trailingAnchor.constraint(equalTo:view.trailingAnchor,constant:-10),
            picker.widthAnchor.constraint(equalToConstant:40),picker.heightAnchor.constraint(equalToConstant:40)
        ])
        audio.event = { [weak self] kind,msg in self?.notify(kind,msg) }
        NotificationCenter.default.addObserver(self,selector:#selector(routes),name:AVAudioSession.routeChangeNotification,object:nil)
        if let page = Bundle.main.url(forResource:"index",withExtension:"html",subdirectory:"assets"){
            web.loadFileURL(page,allowingReadAccessTo:page.deletingLastPathComponent())
        } else {notify("error","HTML assets missing from bundle")}
    }
    private func json(_ s:String)->String {
       let data=try! JSONSerialization.data(withJSONObject:[s])
       return String(String(data:data,encoding:.utf8)!.dropFirst().dropLast())
    }
    private func notify(_ kind:String,_ msg:String) {
        DispatchQueue.main.async { [weak self] in
            guard let self=self else{return}
            self.web?.evaluateJavaScript("window.nativeEvent && window.nativeEvent(\(self.json(kind)),\(self.json(msg)))")
        }
    }
    @objc private func routes() {
        let session=AVAudioSession.sharedInstance()
        var m:[[String:String]]=[["id":"default","kind":"phone","name":"iPhone Microphone","channelCounts":"1"]]
        for x in session.availableInputs ?? [] {
            if x.portType == .usbAudio || x.portType == .builtInMic {
                m.append(["id":x.uid,"kind":x.portType == .usbAudio ? "usb":"phone","name":x.portName,"channelCounts":"\(x.channels?.count ?? 1)"])
            }
        }
        var o:[[String:String]]=[["id":"default","kind":"default","name":"iOS Current Output"]]
        for x in session.currentRoute.outputs {
            let kind:String
            switch x.portType {case .usbAudio:kind="usb";case .headphones,.headsetMic:kind="wired";case .bluetoothA2DP,.bluetoothHFP,.bluetoothLE:kind="bt";default:kind="default"}
            o.append(["id":x.uid,"kind":kind,"name":x.portName])
        }
        if let a=try? JSONSerialization.data(withJSONObject:m),let b=try? JSONSerialization.data(withJSONObject:o),
           let x=String(data:a,encoding:.utf8),let y=String(data:b,encoding:.utf8){
            web.evaluateJavaScript("window.__iosMics=\(x);window.__iosOutputs=\(y);")
        }
    }
    func userContentController(_ u:WKUserContentController,didReceive message:WKScriptMessage) {
        guard let body=message.body as? [String:Any],let method=body["method"] as? String else{return}
        let a=body["args"] as? [Any] ?? []
        func str(_ i:Int,_ d:String="")->String {a.indices.contains(i) ? String(describing:a[i]):d}
        func num(_ i:Int,_ d:Double=0)->Double{a.indices.contains(i) ? (a[i] as? NSNumber)?.doubleValue ?? d:d}
        func bool(_ i:Int)->Bool{a.indices.contains(i) ? (a[i] as? Bool ?? false):false}
        switch method {
        case "savePresets":let p=str(0);if p.utf8.count<20000 {UserDefaults.standard.set(p,forKey:"presets")}
        case "start":audio.start(input:str(0),monitor:bool(1),stage:false)
        case "stage":if bool(3){notify("error","iOS two-channel USB mixing not yet supported. Choose MIC ONLY.")}else{audio.start(input:str(0),monitor:true,stage:true)}
        case "bt":audio.start(input:"default",monitor:true,stage:false)
        case "stop":audio.stop()
        case "params":audio.configure(level:num(0,0.6),feedback:num(1,0.25),milliseconds:num(2,380),mode:str(3,"STANDARD"),master:num(5,0.15),bypass:bool(6))
        case "hall":audio.hall=bool(0);audio.update()
        case "record":audio.record(name:str(0,"Naat"))
        case "recordStop":audio.finish()
        case "play":audio.play()
        case "pick":notify("hint","Use the iOS audio route picker at the top-right for Bluetooth or USB output.")
        case "noise":notify("noise","Advanced Android noise suppression is unavailable in this iOS version.")
        case "loop","warp":notify("hint","This DD-8 processing mode has not been ported to iPhone.")
        default:break
        }
        if ["start","stage","bt"].contains(method) {DispatchQueue.main.asyncAfter(deadline:.now()+0.5){self.routes()}}
    }
}
final class IOSAudio {
    var event:((String,String)->Void)?
    private var engine:AVAudioEngine?
    private var delay:AVAudioUnitDelay?
    private var reverb:AVAudioUnitReverb?
    private var file:AVAudioFile?
    private var last:URL?
    private var player:AVAudioPlayer?
    private var monitor=false
    private var level=0.6,feedback=0.25,milliseconds=380.0,master=0.15
    private var mode="STANDARD", bypass=false
    var hall=false
    func start(input:String,monitor:Bool,stage:Bool){
        stop();self.monitor=monitor
        AVAudioSession.sharedInstance().requestRecordPermission {[weak self] allowed in
            DispatchQueue.main.async {
                guard let self=self else{return}
                guard allowed else {self.event?("error","Allow microphone in iPhone Settings.");return}
                self.startGranted(input:input,stage:stage)
            }
        }
    }
    private func startGranted(input:String,stage:Bool){
        do {
            let session=AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord,mode:.default,options:[.allowBluetoothA2DP,.defaultToSpeaker])
            try session.setPreferredSampleRate(48000)
            try session.setPreferredIOBufferDuration(0.01)
            try session.setActive(true)
            let usb=(session.availableInputs ?? []).first(where:{$0.portType == .usbAudio})
            if stage && usb == nil {event?("error","USB microphone/interface not detected on this iPhone.");return}
            if stage,let port=usb {try session.setPreferredInput(port)}
            else if let mic=(session.availableInputs ?? []).first(where:{$0.portType == .builtInMic}) {try session.setPreferredInput(mic)}
            let g=AVAudioEngine(),d=AVAudioUnitDelay(),r=AVAudioUnitReverb()
            g.attach(d);g.attach(r)
            let fmt=g.inputNode.outputFormat(forBus:0)
            guard fmt.channelCount>0 else {event?("error","No input channels.");return}
            g.connect(g.inputNode,to:d,format:fmt);g.connect(d,to:r,format:fmt);g.connect(r,to:g.mainMixerNode,format:fmt)
            engine=g;delay=d;reverb=r;update()
            r.installTap(onBus:0,bufferSize:1024,format:fmt){[weak self] buf,_ in
                guard let self=self,let output=self.file else{return}
                do {try output.write(from:buf)}catch{self.event?("error","WAV write error.")}
            }
            try g.start()
            event?("status","MIC CONNECTED • iOS \(Int(fmt.sampleRate)) Hz")
            if stage {event?("stage","USB mic-only soundcheck. Use wired/USB output to mixer. iOS stereo instruments unavailable.")}
            event?("latency","Bluetooth speaker/earbuds latency depends on iOS hardware; zero delay not possible.")
        }catch{event?("error","iOS audio error: \(error.localizedDescription)")}
    }
    func configure(level:Double,feedback:Double,milliseconds:Double,mode:String,master:Double,bypass:Bool){
        self.level=level;self.feedback=feedback;self.milliseconds=milliseconds;self.mode=mode;self.master=master;self.bypass=bypass;update()
    }
    func update(){
        delay?.delayTime=min(2,max(0.02,milliseconds/1000))
        delay?.feedback=Float(min(90,max(0,feedback*95)))
        delay?.wetDryMix=bypass ? 0:Float(min(100,max(0,level*100)))
        reverb?.loadFactoryPreset(.largeHall)
        reverb?.wetDryMix = (!bypass && (hall || mode == "+RV")) ? 25:0
        engine?.mainMixerNode.outputVolume=monitor ? Float(master):0
        if !["ANALOG","STANDARD","TAPE","WARM","+RV"].contains(mode) {event?("hint","iOS currently approximates this DD-8 mode with standard delay.")}
    }
    func record(name:String){
        guard engine?.isRunning == true,let rev=reverb else {event?("record","Start mic before recording.");return}
        do {
            let folder=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("Recordings",isDirectory:true)
            try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true)
            let safe=String(name.replacingOccurrences(of:"[^A-Za-z0-9_-]",with:"_",options:.regularExpression).prefix(40))
            let dest=folder.appendingPathComponent("\(safe)_\(Int(Date().timeIntervalSince1970)).wav")
            file=try AVAudioFile(forWriting:dest,settings:rev.outputFormat(forBus:0).settings,commonFormat:.pcmFormatFloat32,interleaved:false)
            last=dest;event?("record","RECORDING WAV")
        }catch{event?("error","Cannot start recording: \(error.localizedDescription)")}
    }
    func finish(){file=nil;event?("record",last == nil ? "NO RECORDING":"WAV SAVED IN DOCUMENTS / RECORDINGS")}
    func play(){
        guard let url=last else {event?("record","No recording yet.");return}
        do{player=try AVAudioPlayer(contentsOf:url);player?.play();event?("record","PLAYING RECORDING")}catch{event?("error","Cannot play WAV.")}
    }
    func stop(){file=nil;if let r=reverb{r.removeTap(onBus:0)};engine?.stop();engine=nil;delay=nil;reverb=nil;event?("status","MIC STOPPED")}
}
