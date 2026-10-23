// Host-side DSP/queue regression tests. AAudio/JNI are supplied by the Android
// headers at compile time; unreachable platform entrypoints are linker-discarded.
#include <cassert>
#include <iostream>
#include "../../../app/src/main/jni/sling_audio.cpp"

static void initialize(Engine& e) {
    e.rate=48000;e.gainStep=1;e.echoL.resize(5040);e.echoR.resize(5040);
    e.uiEchoL.resize(5040);e.uiEchoR.resize(5040);
    e.musicEchoL.resize(16320);e.musicEchoR.resize(16320);
    for(size_t i=0;i<e.curve.size();++i)e.curve[i]=.9f*std::tanh((float(i)/(e.curve.size()-1)*2-1)*1.4f);
    e.muted=false;e.seenGeneration=e.generation.load();e.seenGame=e.gameGeneration.load();e.seenMusic=e.musicGeneration.load();
    e.samples[0]=std::vector<float>(48000,.25f);
}
static void enqueue(Engine& e,int type) {
    Command c;c.type=type;c.count=1;c.generation=e.generation.load();c.gameGeneration=e.gameGeneration.load();c.musicGeneration=e.musicGeneration.load();
    c.parts[0]={0,0,.707f,.707f,false};
    e.queue[e.write.load()%kQueue]=c;e.write.fetch_add(1);
}
int main(){
    Engine e;initialize(e);float block[512];
    enqueue(e,0);e.render(block,256);assert(e.count()==1);const float loud=block[510];assert(loud>.1f);
    e.volume=.25f;e.render(block,256);assert(block[510]>0&&block[510]<loud*.4f);
    e.paused=true;e.gameGeneration.fetch_add(1);e.musicGeneration.fetch_add(1);
    enqueue(e,0);enqueue(e,1);e.render(block,256);assert(e.count()==1);assert(e.voices[0].type==1);assert(block[510]>0);
    e.muted=true;e.generation.fetch_add(1);e.render(block,256);assert(e.count()==0);assert(std::abs(block[510])<.001f);
    e.muted=false;e.paused=false;e.volume=1;
    auto pcm=std::make_shared<std::vector<float>>(48000,.2f);e.retained.push_back(pcm);
    Command c;c.type=2;c.count=1;c.generation=e.generation.load();c.musicGeneration=e.musicGeneration.load();c.music=pcm;c.bus=0;c.parts[0]={0,0,.7f,.7f,true};
    e.levels[0]=1;e.queue[e.write.load()%kQueue]=c;e.write.fetch_add(1);
    for(int i=0;i<100;++i)e.render(block,256);
    assert(e.count()==1);assert(block[510]>.01f);
    e.musicGeneration.fetch_add(1);e.render(block,256);assert(e.count()==0);assert(std::abs(block[510])<.001f);
    for(int i=0;i<200;++i)enqueue(e,0),e.render(block,256);
    assert(e.count()<=64);assert(e.dropped.load()>0);
    std::cout<<"PASS: effects, gain, pause/UI isolation, mute/flush, music, music stop, voice bounds\n";
}
