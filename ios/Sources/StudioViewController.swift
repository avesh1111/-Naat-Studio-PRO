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
        case "stage":audio.start(input:str(0),monitor:true,stage:true,stereo:bool(3),outputID:str(2,"default"))
        case "bt":audio.start(input:"default",monitor:true,stage:false)
        case "stop":audio.stop()
        case "params":audio.configure(level:num(0,0.6),feedback:num(1,0.25),milliseconds:num(2,380),mode:str(3,"STANDARD"),gate:num(4,-50),master:num(5,0.15),bypass:bool(6))
        case "hall":audio.setHall(bool(0))
        case "record":audio.record(name:str(0,"Naat"),raw:bool(1))
        case "recordStop":audio.finish()
        case "play":audio.play()
        case "pick":notify("hint","Use the iOS audio route picker at the top-right for Bluetooth or USB output.")
        case "noise":audio.setNoise(Int(num(0,2)))
        case "mix":audio.setMix(vocal:num(0,0.65),instrument:num(1,0.55))
        case "loop":audio.loop(str(0))
        case "warp":audio.setWarp(bool(0))
        default:break
        }
        if ["start","stage","bt"].contains(method) {DispatchQueue.main.asyncAfter(deadline:.now()+0.5){self.routes()}}
    }
}
