// AAudio output for SlingBreak's offline-rendered voice samples.
// JNI/control operations are serialized; the realtime callback never allocates or locks.
#include <aaudio/AAudio.h>
#include <jni.h>
#include <dlfcn.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace {
constexpr int kSamples = 512, kVoices = 128, kBatch = 16, kQueue = 128;
constexpr float kPi = 3.14159265358979323846f;
struct Part { int id=0, delay=0; float left=0, right=0; bool wet=false; };
struct Command {
    std::array<Part,kBatch> parts{};
    int count=0, type=0; bool priority=false;
    uint32_t generation=0;
    uint32_t gameGeneration=0, musicGeneration=0;
    std::shared_ptr<std::vector<float>> music;
    int bus=0;
};
struct Voice {
    const std::vector<float>* pcm=nullptr;
    int position=0, delay=0, type=0;
    float left=0,right=0; bool wet=false,priority=false;
    uint64_t order=0;
    std::shared_ptr<std::vector<float>> music;
    int bus=0;
};
struct Engine {
    AAudioStream* stream=nullptr;
    int rate=0,burst=0,buffer=0,capacity=0,mode=0,sharing=0,lastXruns=0;
    bool committed=false,active=true;
    std::atomic<bool> muted{true},failed{false};
    std::atomic<int> error{0},voiceCount{0},dropped{0};
    std::atomic<uint64_t> accepted{0},renderedFrames{0};
    std::atomic<uint32_t> generation{1},write{0},read{0};
    std::atomic<uint32_t> gameGeneration{1},musicGeneration{1};
    std::atomic<bool> paused{false};
    std::atomic<float> volume{1},cutoff{18000},fadeTarget{1};
    std::atomic<int> fadeFrames{0};
    std::array<std::atomic<float>,7> levels{};
    std::array<float,7> smoothLevels{};
    std::unordered_map<int,std::shared_ptr<std::vector<float>>> musicSamples;
    std::vector<std::shared_ptr<std::vector<float>>> retained;
    size_t musicBytes=0;
    uint32_t seenGame=0,seenMusic=0;
    float musicGain=1,musicCutoff=18000,musicL=0,musicR=0;
    std::vector<float> uiEchoL,uiEchoR,musicEchoL,musicEchoR;
    size_t musicEchoPosition=0;
    std::array<Command,kQueue> queue{};
    std::array<std::vector<float>,kSamples> samples;
    std::array<Voice,kVoices> voices{};
    std::vector<float> echoL,echoR;
    std::array<float,2048> curve{};
    uint32_t seenGeneration=0;
    uint64_t order=0;
    size_t echoPosition=0;
    size_t totalSamples=0;
    float gain=0,gainStep=0;

    ~Engine(){ if(stream){AAudioStream_requestStop(stream);AAudioStream_close(stream);} }
    static void onError(AAudioStream*,void* data,aaudio_result_t code){
        auto* e=static_cast<Engine*>(data);
        e->error.store(code);e->failed.store(true);
        // Closing here can deadlock: the control thread owns destruction.
    }
    static aaudio_data_callback_result_t callback(AAudioStream*,void* data,void* output,int32_t frames){
        return static_cast<Engine*>(data)->render(static_cast<float*>(output),frames);
    }
    bool open(){
        // Try exclusive first; keep the shared path for devices that reject it.
        for(auto share:{AAUDIO_SHARING_MODE_EXCLUSIVE,AAUDIO_SHARING_MODE_SHARED}){
            AAudioStreamBuilder* builder=nullptr;
            if(AAudio_createStreamBuilder(&builder)!=AAUDIO_OK)return false;
            AAudioStreamBuilder_setDirection(builder,AAUDIO_DIRECTION_OUTPUT);
            AAudioStreamBuilder_setFormat(builder,AAUDIO_FORMAT_PCM_FLOAT);
            AAudioStreamBuilder_setChannelCount(builder,2);
            AAudioStreamBuilder_setSharingMode(builder,share);
            AAudioStreamBuilder_setPerformanceMode(builder,AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            // Optional newer setters: keep this library loadable on API 26.
            using AttributeSetter=void (*)(AAudioStreamBuilder*,int32_t);
            const auto setUsage=reinterpret_cast<AttributeSetter>(dlsym(RTLD_DEFAULT,"AAudioStreamBuilder_setUsage"));
            const auto setContent=reinterpret_cast<AttributeSetter>(dlsym(RTLD_DEFAULT,"AAudioStreamBuilder_setContentType"));
            if(setUsage)setUsage(builder,AAUDIO_USAGE_GAME);
            if(setContent)setContent(builder,AAUDIO_CONTENT_TYPE_SONIFICATION);
            AAudioStreamBuilder_setDataCallback(builder,callback,this);
            AAudioStreamBuilder_setErrorCallback(builder,onError,this);
            const auto result=AAudioStreamBuilder_openStream(builder,&stream);
            AAudioStreamBuilder_delete(builder);
            if(result==AAUDIO_OK)break;
            stream=nullptr;
        }
        if(!stream)return false;
        rate=AAudioStream_getSampleRate(stream);
        burst=AAudioStream_getFramesPerBurst(stream);
        capacity=AAudioStream_getBufferCapacityInFrames(stream);
        mode=AAudioStream_getPerformanceMode(stream);
        sharing=AAudioStream_getSharingMode(stream);
        if(rate<8000||rate>192000||burst<=0||capacity<=0||AAudioStream_getChannelCount(stream)!=2||
           AAudioStream_getFormat(stream)!=AAUDIO_FORMAT_PCM_FLOAT)return false;
        AAudioStream_setBufferSizeInFrames(stream,std::min(capacity,burst*2));
        buffer=AAudioStream_getBufferSizeInFrames(stream);
        // Do not replace Web Audio with another known-large-buffer path.
        if(buffer<=0||static_cast<double>(buffer)/rate>=.05)return false;
        echoL.resize(static_cast<size_t>(std::round(rate*.105)),0);
        echoR.resize(echoL.size(),0);
        uiEchoL.resize(echoL.size(),0);uiEchoR.resize(echoL.size(),0);
        musicEchoL.resize(static_cast<size_t>(std::round(rate*.34)),0);musicEchoR.resize(musicEchoL.size(),0);
        for(size_t i=0;i<curve.size();++i)curve[i]=.9f*std::tanh((float(i)/(curve.size()-1)*2-1)*1.4f);
        gainStep=1-std::exp(-1.f/(rate*.012f));
        return true;
    }
    bool waitFor(aaudio_stream_state_t wanted){
        for(int i=0;i<10&&!failed.load();++i){
            auto state=AAudioStream_getState(stream);
            if(state==wanted)return true;
            if(state==AAUDIO_STREAM_STATE_DISCONNECTED)return false;
            aaudio_stream_state_t next;
            AAudioStream_waitForStateChange(stream,state,&next,10000000);
        }
        return false;
    }
    bool start(){
        if(failed.load()||!active)return false;
        if(AAudioStream_requestStart(stream)!=AAUDIO_OK||!waitFor(AAUDIO_STREAM_STATE_STARTED)){
            failed.store(true);return false;
        }
        return true;
    }
    bool setActive(bool value){
        if(active==value)return !failed.load();
        active=value;muted.store(true);generation.fetch_add(1);
        if(!committed)return !failed.load();
        if(value)return start();
        if(AAudioStream_requestPause(stream)!=AAUDIO_OK||!waitFor(AAUDIO_STREAM_STATE_PAUSED)||
           AAudioStream_requestFlush(stream)!=AAUDIO_OK||!waitFor(AAUDIO_STREAM_STATE_FLUSHED)){
            failed.store(true);return false;
        }
        return true;
    }
    float limit(float value) const {
        const float index=(std::clamp(value,-1.f,1.f)+1)*.5f*(curve.size()-1);
        const size_t lo=static_cast<size_t>(index),hi=std::min(lo+1,curve.size()-1);
        return curve[lo]+(curve[hi]-curve[lo])*(index-lo);
    }
    int count()const{int n=0;for(const auto& v:voices)if(v.pcm)++n;return n;}
    void clear(){
        for(auto& v:voices){v.pcm=nullptr;v.music.reset();}
        std::fill(echoL.begin(),echoL.end(),0);std::fill(echoR.begin(),echoR.end(),0);
        std::fill(uiEchoL.begin(),uiEchoL.end(),0);std::fill(uiEchoR.begin(),uiEchoR.end(),0);
        clearMusic();
        gain=0;
    }
    void clearMusic(){
        for(auto& v:voices)if(v.type==2){v.pcm=nullptr;v.music.reset();}
        std::fill(musicEchoL.begin(),musicEchoL.end(),0);std::fill(musicEchoR.begin(),musicEchoR.end(),0);
        musicL=musicR=0;musicGain=1;remainingFade=0;
    }
    void consume(const Command& c){
        int n=0;for(const auto& v:voices)if(v.pcm&&v.type==c.type)++n;
        const int limit=c.type==2?48:c.type==1?16:64;
        if(n+c.count>limit&&!c.priority){dropped.fetch_add(1);return;}
        if(c.priority&&n+c.count>limit){
            while(n+c.count>limit){
                Voice* victim=nullptr;int best=4;
                for(auto& v:voices)if(v.pcm&&v.type==c.type){
                    const int score=v.priority?(v.type==c.type?1:2):0;
                    if(!victim||score<best||(score==best&&v.order<victim->order)){victim=&v;best=score;}
                }
                if(!victim)break;
                victim->pcm=nullptr;victim->music.reset();--n;
            }
        }
        if(count()+c.count>kVoices){dropped.fetch_add(1);return;}
        for(int i=0;i<c.count;++i)for(auto& v:voices)if(!v.pcm){
            const auto& p=c.parts[i];v={c.music?c.music.get():&samples[p.id],0,p.delay,c.type,p.left,p.right,p.wet,c.priority,++order,c.music,c.bus};break;
        }
    }
    aaudio_data_callback_result_t render(float* out,int frames){
        const auto gen=generation.load();
        if(gen!=seenGeneration){clear();seenGeneration=gen;}
        const auto gg=gameGeneration.load(),mg=musicGeneration.load();
        if(gg!=seenGame){for(auto& v:voices)if(v.type==0){v.pcm=nullptr;}std::fill(echoL.begin(),echoL.end(),0);std::fill(echoR.begin(),echoR.end(),0);seenGame=gg;}
        if(mg!=seenMusic){clearMusic();seenMusic=mg;}
        const bool silent=muted.load()||failed.load();
        auto r=read.load(std::memory_order_relaxed);
        const auto w=write.load(std::memory_order_acquire);
        while(r!=w){auto& c=queue[r%kQueue];if(!silent&&c.generation==gen&&(c.type==1||!paused.load())&&(c.type!=0||c.gameGeneration==gg)&&(c.type!=2||c.musicGeneration==mg))consume(c);c.music.reset();++r;}
        read.store(r,std::memory_order_release);
        const float target=silent?0:.7f*volume.load();
        musicCutoff+=(cutoff.load()-musicCutoff)*(1-std::exp(-float(frames)/(.3f*rate)));
        const float coefficient=1-std::exp(-2*kPi*std::min(musicCutoff,rate*.49f)/rate),smoothing=1-std::exp(-1.f/(.4f*rate));
        int fade=fadeFrames.exchange(0);const float fadeTo=fadeTarget.load();
        if(fade>0){remainingFade=fade;}
        for(int f=0;f<frames;++f){
            for(int b=0;b<7;++b)smoothLevels[b]+=(levels[b].load()-smoothLevels[b])*smoothing;
            if(remainingFade>0){musicGain+=(fadeTo-musicGain)/remainingFade;--remainingFade;}
            float l=0,rgt=0,sendL=0,sendR=0,ul=0,ur=0,ml=0,mr=0,mwl=0,mwr=0;
            for(auto& v:voices)if(v.pcm){
                if(v.delay>0){--v.delay;continue;}
                if(v.position>=static_cast<int>(v.pcm->size())){v.pcm=nullptr;v.music.reset();continue;}
                const float sample=(*v.pcm)[v.position++],sl=sample*v.left,sr=sample*v.right;
                if(v.type==2){ml+=sl*smoothLevels[v.bus];mr+=sr*smoothLevels[v.bus];if(v.wet){mwl+=sl;mwr+=sr;}continue;}
                l+=sl;rgt+=sr;if(v.wet){if(v.type==1){ul+=sl;ur+=sr;}else{sendL+=sl;sendR+=sr;}}
            }
            const float el=echoL[echoPosition],er=echoR[echoPosition];
            echoL[echoPosition]=sendL+.18f*el;echoR[echoPosition]=sendR+.18f*er;
            const float il=uiEchoL[echoPosition],ir=uiEchoR[echoPosition];
            uiEchoL[echoPosition]=ul+.18f*il;uiEchoR[echoPosition]=ur+.18f*ir;
            const float mel=musicEchoL[musicEchoPosition],mer=musicEchoR[musicEchoPosition];
            musicEchoL[musicEchoPosition]=mwl+.32f*mel;musicEchoR[musicEchoPosition]=mwr+.32f*mer;
            if(++musicEchoPosition==musicEchoL.size())musicEchoPosition=0;
            musicL+=coefficient*(ml+.28f*mel-musicL);musicR+=coefficient*(mr+.28f*mer-musicR);
            if(++echoPosition==echoL.size())echoPosition=0;
            gain+=(target-gain)*gainStep;
            out[f*2]=limit((l+.16f*(el+il)+musicL*musicGain*(.55f/.7f))*gain);out[f*2+1]=limit((rgt+.16f*(er+ir)+musicR*musicGain*(.55f/.7f))*gain);
        }
        voiceCount.store(count());
        renderedFrames.fetch_add(frames,std::memory_order_relaxed);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    int remainingFade=0;
};
std::mutex control;
std::unordered_map<jlong,std::unique_ptr<Engine>> engines;
jlong nextId=1;
Engine* get(jlong id){auto i=engines.find(id);return i==engines.end()?nullptr:i->second.get();}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeCreate(JNIEnv*,jobject){
    std::lock_guard<std::mutex> lock(control);
    try{auto engine=std::make_unique<Engine>();if(!engine->open())return 0;
        const auto id=nextId++;engines.emplace(id,std::move(engine));return id;
    }catch(...){return 0;}
}
extern "C" JNIEXPORT jstring JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeInfo(JNIEnv* env,jobject,jlong id){
    std::lock_guard<std::mutex> lock(control);const auto* e=get(id);
    if(!e)return env->NewStringUTF("{\"error\":\"no-stream\"}");
    char json[768];
    std::snprintf(json,sizeof(json),"{\"version\":1,\"backend\":\"native-aaudio\",\"session\":%lld,\"sampleRate\":%d,\"framesPerBurst\":%d,\"bufferFrames\":%d,\"capacityFrames\":%d,\"bufferDurationMs\":%.3f,\"performanceMode\":%d,\"sharingMode\":%d,\"streamState\":%d,\"error\":%d,\"failed\":%s,\"activeVoices\":%d,\"dropped\":%d,\"xruns\":%d,\"accepted\":%llu,\"renderedFrames\":%llu,\"gain\":%.3f,\"paused\":%s,\"musicSamples\":%zu}",
        static_cast<long long>(id),e->rate,e->burst,e->buffer,e->capacity,1000.0*e->buffer/e->rate,e->mode,e->sharing,
        AAudioStream_getState(e->stream),e->error.load(),e->failed.load()?"true":"false",e->voiceCount.load(),e->dropped.load(),AAudioStream_getXRunCount(e->stream),
        static_cast<unsigned long long>(e->accepted.load()),static_cast<unsigned long long>(e->renderedFrames.load()),e->volume.load(),e->paused.load()?"true":"false",e->musicSamples.size());
    return env->NewStringUTF(json);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeUpload(JNIEnv* env,jobject,jlong id,jint sample,jfloatArray pcm){
    std::lock_guard<std::mutex> lock(control);auto* e=get(id);
    if(!e||e->committed||sample<0||sample>=kSamples||!pcm)return false;
    const int size=env->GetArrayLength(pcm);
    if(size<1||size>e->rate*2||!e->samples[sample].empty()||e->totalSamples+size>8388608)return false;
    try{
        std::vector<float> data(size);env->GetFloatArrayRegion(pcm,0,size,data.data());
        if(env->ExceptionCheck())return false;
        for(float v:data)if(!std::isfinite(v)||std::abs(v)>4)return false;
        e->samples[sample]=std::move(data);e->totalSamples+=size;return true;
    }catch(...){return false;}
}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeCommit(JNIEnv*,jobject,jlong id){
    std::lock_guard<std::mutex> lock(control);auto* e=get(id);
    if(!e||e->committed||!e->totalSamples)return false;
    e->committed=true;return e->start();
}
extern "C" JNIEXPORT jint JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativePlay(JNIEnv* env,jobject,jlong id,jboolean ui,jboolean priority,jintArray ids,jfloatArray params){
    std::lock_guard<std::mutex> lock(control);auto* e=get(id);
    if(!e||!e->committed||e->failed.load())return -1;
    if(e->muted.load()||!e->active)return 0;
    if(!ids||!params)return -2;
    const int n=env->GetArrayLength(ids);
    if(n<1||n>kBatch||env->GetArrayLength(params)!=n*3)return -2;
    std::array<jint,kBatch> indexes{};std::array<jfloat,kBatch*3> values{};
    env->GetIntArrayRegion(ids,0,n,indexes.data());env->GetFloatArrayRegion(params,0,n*3,values.data());
    if(env->ExceptionCheck())return -2;
    Command c;c.count=n;c.type=ui?1:0;c.priority=priority;c.generation=e->generation.load();c.gameGeneration=e->gameGeneration.load();
    if(!ui&&e->paused.load())return 0;
    for(int i=0;i<n;++i){
        const int sample=indexes[i];const float delay=values[i*3],pan=values[i*3+1],wet=values[i*3+2];
        if(sample<0||sample>=kSamples||e->samples[sample].empty()||!std::isfinite(delay)||delay<0||delay>2||!std::isfinite(pan)||std::abs(pan)>1||!std::isfinite(wet))return -2;
        const float angle=(pan+1)*kPi/4;
        c.parts[i]={sample,static_cast<int>(std::round(delay*e->rate)),std::cos(angle),std::sin(angle),wet>0};
    }
    const auto w=e->write.load(std::memory_order_relaxed),r=e->read.load(std::memory_order_acquire);
    if(w-r>=kQueue){e->dropped.fetch_add(1);return 0;}
    e->queue[w%kQueue]=c;e->write.store(w+1,std::memory_order_release);e->accepted.fetch_add(1);return 1;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeMute(JNIEnv*,jobject,jlong id,jboolean muted){
    std::lock_guard<std::mutex> lock(control);auto* e=get(id);if(!e||e->failed.load())return false;
    if(e->muted.exchange(muted)&&muted)return true;
    if(muted)e->generation.fetch_add(1);
    return true;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeActive(JNIEnv*,jobject,jlong id,jboolean active){
    std::lock_guard<std::mutex> lock(control);auto* e=get(id);return e&&e->setActive(active);
}
extern "C" JNIEXPORT void JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeMaintain(JNIEnv*,jobject,jlong id){
    std::lock_guard<std::mutex> lock(control);auto* e=get(id);
    if(!e||!e->committed||!e->active||e->failed.load())return;
    e->retained.erase(std::remove_if(e->retained.begin(),e->retained.end(),[](const auto& p){return p.use_count()==1;}),e->retained.end());
    const int xruns=AAudioStream_getXRunCount(e->stream);
    if(xruns>e->lastXruns){
        const int target=std::min(e->capacity,e->buffer+e->burst);
        // Bounded adaptation: prefer stable Web Audio over repeated underruns.
        if(target<=e->buffer||static_cast<double>(target)/e->rate>=.05){
            e->failed.store(true);e->error.store(AAUDIO_ERROR_UNAVAILABLE);
        }else{
            const auto result=AAudioStream_setBufferSizeInFrames(e->stream,target);
            if(result<=0||static_cast<double>(result)/e->rate>=.05){
                e->failed.store(true);e->error.store(result<0?result:AAUDIO_ERROR_UNAVAILABLE);
            }else e->buffer=result;
        }
    }
    e->lastXruns=xruns;
}
extern "C" JNIEXPORT void JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeRelease(JNIEnv*,jobject,jlong id){
    std::lock_guard<std::mutex> lock(control);engines.erase(id);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeState(JNIEnv*,jobject,jlong id,jfloat gain,jboolean muted,jboolean paused){
    std::lock_guard<std::mutex> lock(control);auto* e=get(id);
    if(!e||e->failed.load()||!std::isfinite(gain)||gain<0||gain>1)return false;
    e->volume.store(gain);
    if(e->muted.exchange(muted)!=bool(muted)&&muted)e->generation.fetch_add(1);
    if(e->paused.exchange(paused)!=bool(paused)&&paused){e->gameGeneration.fetch_add(1);e->musicGeneration.fetch_add(1);}
    return true;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeUploadMusic(JNIEnv* env,jobject,jlong id,jint sample,jfloatArray pcm){
    std::lock_guard<std::mutex> lock(control);auto* e=get(id);
    if(!e||!e->committed||e->failed.load()||sample<0||!pcm)return false;
    const int n=env->GetArrayLength(pcm);
    if(n<1||n>2097152)return false;
    try{
        auto data=std::make_shared<std::vector<float>>(n);env->GetFloatArrayRegion(pcm,0,n,data->data());
        if(env->ExceptionCheck())return false;
        for(float v:*data)if(!std::isfinite(v)||std::abs(v)>4)return false;
        auto old=e->musicSamples.find(sample);
        if(old!=e->musicSamples.end()){e->musicBytes-=old->second->size()*4;e->musicSamples.erase(old);}
        while(!e->musicSamples.empty()&&(e->musicSamples.size()>=128||e->musicBytes+n*4>64*1024*1024)){
            auto it=e->musicSamples.begin();e->musicBytes-=it->second->size()*4;e->musicSamples.erase(it);
        }
        e->retained.erase(std::remove_if(e->retained.begin(),e->retained.end(),[](const auto& p){return p.use_count()==1;}),e->retained.end());
        size_t retainedBytes=0;for(const auto& p:e->retained)retainedBytes+=p->size()*4;
        if(retainedBytes+n*4>96*1024*1024)return false;
        // Keep ownership on the control thread: the callback never frees sample buffers.
        e->retained.push_back(data);e->musicSamples[sample]=data;e->musicBytes+=n*4;return true;
    }catch(...){return false;}
}
extern "C" JNIEXPORT jint JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativePlayMusic(JNIEnv*,jobject,jlong id,jint sample,jfloat delay,jfloat pan,jfloat volume,jint bus,jboolean wet){
    std::lock_guard<std::mutex> lock(control);auto* e=get(id);
    if(!e||!e->committed||e->failed.load())return -1;
    auto it=e->musicSamples.find(sample);if(it==e->musicSamples.end())return -2;
    if(!std::isfinite(delay)||delay<0||delay>3||!std::isfinite(pan)||std::abs(pan)>1||!std::isfinite(volume)||volume<0||volume>1||bus<0||bus>=7)return -1;
    if(e->muted.load()||!e->active||e->paused.load())return 0;
    Command c;c.count=1;c.type=2;c.generation=e->generation.load();c.musicGeneration=e->musicGeneration.load();c.music=it->second;c.bus=bus;
    const float angle=(pan+1)*kPi/4;c.parts[0]={0,static_cast<int>(std::round(delay*e->rate)),std::cos(angle)*volume,std::sin(angle)*volume,bool(wet)};
    const auto w=e->write.load(std::memory_order_relaxed),r=e->read.load(std::memory_order_acquire);
    if(w-r>=kQueue){e->dropped.fetch_add(1);return 0;}
    e->queue[w%kQueue]=c;e->write.store(w+1,std::memory_order_release);e->accepted.fetch_add(1);return 1;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeMusicState(JNIEnv* env,jobject,jlong id,jfloatArray levels,jfloat cutoff,jboolean stop){
    std::lock_guard<std::mutex> lock(control);auto* e=get(id);
    if(!e||e->failed.load()||!levels||env->GetArrayLength(levels)!=7||!std::isfinite(cutoff)||cutoff<30||cutoff>22050)return false;
    float values[7];env->GetFloatArrayRegion(levels,0,7,values);if(env->ExceptionCheck())return false;
    for(float v:values)if(!std::isfinite(v)||v<0||v>2)return false;
    for(int i=0;i<7;++i)e->levels[i].store(values[i]);e->cutoff.store(cutoff);
    if(stop){e->musicGeneration.fetch_add(1);e->fadeTarget.store(1);e->fadeFrames.store(0);}
    return true;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeMusicFade(JNIEnv*,jobject,jlong id,jfloat target,jfloat seconds){
    std::lock_guard<std::mutex> lock(control);auto* e=get(id);
    if(!e||e->failed.load()||!std::isfinite(target)||target<0||target>1||!std::isfinite(seconds)||seconds<0||seconds>10)return false;
    e->fadeTarget.store(target);e->fadeFrames.store(std::max(1,static_cast<int>(seconds*e->rate)));return true;
}
