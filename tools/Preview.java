import java.awt.*;
import java.awt.geom.*;
import java.awt.font.FontRenderContext;
import java.awt.MultipleGradientPaint;
import java.awt.image.BufferedImage;
import java.io.File;
import java.awt.image.BufferedImage;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Desktop port of DashView's geometry so the 800x480 layout can be judged without a car.
 * It mirrors the layout maths, not the code; keep it in step by hand. Animation state is
 * passed in as a frozen frame rather than run.
 */
public class Preview {
    static final int RPM=13,COOLANT=14,SPEED=17,GEAR=22,ACCEL=23,BRAKE=24,STEER=25,
        G_LAT=20,G_LONG=21,TP_FR=36,TP_FL=37,TP_RR=38,TP_RL=39;
    static final float PEDAL_DEAD=1.5f;
    static final float COOLANT_MIN=40f, COOLANT_MAX=120f, COOLANT_COLD=60f, COOLANT_WARN=105f;
    static final float TPMS_LOW=30f, TPMS_HIGH=44f, STEER_FULL=390f, TPMS_PRESENT=1f, G_ALERT=0.60f;
    static final float BRAKE_FULL=90f, ACCEL_FULL=100f, G_FULL=1.0f;
    static final Color BG_TOP=c(0xFF060A12), BG_BOT=c(0xFF0C1420);
    static final Color CYAN=c(0xFF3FD2FF), CYAN_DIM=c(0xFF1B4E66);
    static final Color WHITE=c(0xFFEAF6FF), GREY=c(0xFF55697C), DARK=c(0xFF13212C);
    static final Color AMBER=c(0xFFFFB020), RED=c(0xFFFF4545), GREEN=c(0xFF46E08A);
    static final Color PANEL=c(0xCC0A1420);
    static final int TRAIL=22;
    static Color c(int a){ return new Color((a>>16)&255,(a>>8)&255,a&255,(a>>>24)&255); }
    static Color al(Color x,int a){ return new Color(x.getRed(),x.getGreen(),x.getBlue(),a); }

    static float[] v=new float[64]; static boolean[] have=new boolean[64];
    static void set(int t,float x){ v[t]=x; have[t]=true; }
    static float g(int t){ return have[t]?v[t]:0f; }
    static boolean h(int t){ return have[t]; }

    static float W=800,H=480;
    static float rpmX,rpmY0,rpmY1,barW,cooX,carCx,carCy,carW,carH,gCx,gCy,gR;
    static Rectangle2D.Float steerBox, pedalBox, coolBox, speedBox, statusBar;
    static Rectangle2D.Float[] tyreBox=new Rectangle2D.Float[4];
    static Rectangle2D.Float gearBox;
    static float[] tdx=new float[4],tdy=new float[4];
    static int[] tyreType={TP_FL,TP_FR,TP_RL,TP_RR};

    static Graphics2D G; static boolean cjk=true; static String FONT="WenQuanYi Zen Hei";
    static Font numFont=null;               // the packaged display face, if one was given
    static float cellDigit=0.6f, cellDot=0.3f, cellMinus=0.4f;

    /** measure the widest digit once, so numbers can be drawn on a fixed cell */
    static void measureCells(Font f){
        FontRenderContext frc=new FontRenderContext(null,true,true);
        Font at100=f.deriveFont(100f);
        float mx=0;
        for(char ch='0';ch<='9';ch++){
            float w=(float)at100.getStringBounds(String.valueOf(ch),frc).getWidth();
            if(w>mx) mx=w;
        }
        cellDigit=mx/100f;
        cellDot=(float)at100.getStringBounds(".",frc).getWidth()/100f;
        cellMinus=(float)at100.getStringBounds("-",frc).getWidth()/100f;
    }
    static float cellFor(char ch){
        if(ch=='.') return cellDot;
        if(ch=='-') return cellMinus;
        return cellDigit;
    }
    static float numWidth(String t,float size){
        float w=0; for(int i=0;i<t.length();i++) w+=cellFor(t.charAt(i))*size; return w;
    }
    /** draw a number with a fixed cell per character: no jitter, whatever the font */
    static void num(String t,float x,float y,int align){
        float size=G.getFont().getSize2D();
        float total=numWidth(t,size);
        float sx = align==0?x : align==1?x-total/2f : x-total;
        FontMetrics fm=G.getFontMetrics();
        for(int i=0;i<t.length();i++){
            char ch=t.charAt(i);
            float cw=cellFor(ch)*size;
            float gw=fm.charWidth(ch);
            G.drawString(String.valueOf(ch), sx+(cw-gw)/2f, y);
            sx+=cw;
        }
    }
    static float pulse=1f, peakThr=0f, peakBrk=0f, maxG=0f; static boolean gearFlash=false;
    static float[] trailX=new float[TRAIL], trailY=new float[TRAIL]; static int trailN=0;

    static void layout(){
        barW=W*0.0625f; rpmX=W*0.0225f; cooX=W-rpmX-barW; rpmY0=H*0.225f; rpmY1=H*0.929f;
        float cellT=H*0.0583f, cellH=H*0.125f, cellW=W*0.115f;
        pedalBox=new Rectangle2D.Float(rpmX,cellT,cellW,cellH);
        gearBox=new Rectangle2D.Float(W*0.185f,cellT,W*0.10f,cellH);
        steerBox=new Rectangle2D.Float(W*0.60f,cellT,W*0.20f,cellH);
        coolBox=new Rectangle2D.Float(cooX+barW-cellW,cellT,cellW,cellH);
        statusBar=new Rectangle2D.Float(W*0.325f,H*0.845f,W*0.370f,H*0.075f);
        speedBox=new Rectangle2D.Float(W*0.705f,H*0.800f,W*0.190f,H*0.160f);
        carW=W*0.255f; carH=H*0.46f; carCx=W*0.5f; carCy=H*0.555f;
        float bw=W*0.1875f,bh=H*0.125f,leftX=W*0.11f,rightX=W*0.7025f,topY=H*0.335f,botY=H*0.585f;
        tyreBox[0]=new Rectangle2D.Float(leftX,topY,bw,bh);
        tyreBox[1]=new Rectangle2D.Float(rightX,topY,bw,bh);
        tyreBox[2]=new Rectangle2D.Float(leftX,botY,bw,bh);
        tyreBox[3]=new Rectangle2D.Float(rightX,botY,bw,bh);
        tdx[0]=carCx-carW*0.46f; tdy[0]=carCy-carH*0.22f;
        tdx[1]=carCx+carW*0.46f; tdy[1]=carCy-carH*0.22f;
        tdx[2]=carCx-carW*0.46f; tdy[2]=carCy+carH*0.28f;
        tdx[3]=carCx+carW*0.46f; tdy[3]=carCy+carH*0.28f;
        gR=H*0.080f; gCx=W*0.15f; gCy=H*0.855f;

    }
    static void font(float px,boolean mono){
        if(mono && numFont!=null) G.setFont(numFont.deriveFont(px));
        else G.setFont(new Font(mono?"monospaced":FONT,Font.PLAIN,Math.round(px)));
    }
    static float tw(String s){ return G.getFontMetrics().stringWidth(s); }
    static void text(String s,float x,float y,int align){
        float w=tw(s); G.drawString(s, align==0?x:align==1?x-w/2f:x-w, y);
    }
    static void rectF(float l,float t,float r,float b,Color col){ G.setColor(col); G.fill(new Rectangle2D.Float(l,t,r-l,b-t)); }
    static void rectS(float l,float t,float r,float b,Color col,float sw){ G.setColor(col); G.setStroke(new BasicStroke(sw)); G.draw(new Rectangle2D.Float(l,t,r-l,b-t)); }
    static void corners(float l,float t,float r,float b,float len){
        G.draw(new Line2D.Float(l,t+len,l,t)); G.draw(new Line2D.Float(l,t,l+len,t));
        G.draw(new Line2D.Float(r-len,t,r,t)); G.draw(new Line2D.Float(r,t,r,t+len));
        G.draw(new Line2D.Float(l,b-len,l,b)); G.draw(new Line2D.Float(l,b,l+len,b));
        G.draw(new Line2D.Float(r-len,b,r,b)); G.draw(new Line2D.Float(r,b,r,b-len));
    }
    static void frame(float l,float t,float r,float b){
        G.setPaint(new GradientPaint(0,t,c(0xD8101E2C),0,b,PANEL));
        G.fill(new Rectangle2D.Float(l,t,r-l,b-t));
        G.setPaint(null); rectS(l,t,r,b,c(0x4A2F5E74),1.1f);
        float len=Math.min(Math.min(r-l,b-t)*0.26f,16f);
        G.setColor(c(0x553FD2FF)); G.setStroke(new BasicStroke(2.4f)); corners(l,t,r,b,len);
        G.setColor(CYAN); G.setStroke(new BasicStroke(1.2f)); corners(l,t,r,b,len);
    }
    static void frame(Rectangle2D.Float b){ frame((float)b.getMinX(),(float)b.getMinY(),(float)b.getMaxX(),(float)b.getMaxY()); }
    static void backdrop(){
        G.setPaint(new LinearGradientPaint(new Point2D.Float(0,0),new Point2D.Float(0,H),
            new float[]{0f,0.28f,0.72f,1f},
            new Color[]{c(0xFF05080F),BG_TOP,c(0xFF0E1826),BG_BOT}));
        G.fillRect(0,0,(int)W,(int)H);
        G.setPaint(new RadialGradientPaint(new Point2D.Float(W*0.5f,H*0.46f),W*0.72f,
            new float[]{0f,0.55f,1f},
            new Color[]{new Color(0,0,0,0),new Color(0,0,0,0),new Color(0,0,0,0x5C)}));
        G.fillRect(0,0,(int)W,(int)H);
        G.setPaint(null);
    }
    static void arcs(){
        for(int i=0;i<6;i++){
            float rad=carW*(0.95f+i*0.30f);
            G.setColor(al(CYAN,0x14-i*2));
            G.setStroke(new BasicStroke(i==2?1.6f:1.0f));
            G.draw(new Arc2D.Float(carCx-rad,carCy-rad,rad*2,rad*2,-340,140,Arc2D.OPEN));
            G.draw(new Arc2D.Float(carCx-rad,carCy-rad,rad*2,rad*2,-160,140,Arc2D.OPEN));
        }
    }
    static void pool(){
        G.setPaint(new RadialGradientPaint(new Point2D.Float(carCx,carCy),carW*1.25f,
            new float[]{0f,0.45f,1f},
            new Color[]{c(0x2A3FD2FF),c(0x0E3FD2FF),new Color(0,0,0,0)}));
        G.fill(new Rectangle2D.Float(carCx-carW*1.3f,carCy-carW*1.3f,carW*2.6f,carW*2.6f));
        G.setPaint(null);
    }
    static void columnScale(float edgeX,boolean leftOf,int div){
        float inner=barW*0.18f, y0=rpmY0+inner, y1=rpmY1-inner;
        for(int i=0;i<=div*2;i++){
            boolean major=(i%2)==0;
            float y=y1-(y1-y0)*i/(div*2f), len=major?7f:3.5f;
            G.setStroke(new BasicStroke(major?1.6f:1.0f));
            G.setColor(c(major?0x993FD2FF:0x443FD2FF));
            if(leftOf) G.draw(new Line2D.Float(edgeX-3-len,y,edgeX-3,y));
            else G.draw(new Line2D.Float(edgeX+3,y,edgeX+3+len,y));
        }
    }
    static void headerCell(Rectangle2D.Float b,String cap){
        frame(b); label(cap,(float)b.getCenterX(),(float)b.getWidth(),(float)b.getMinY()-H*0.0125f);
    }
    static void label(String s,float cx,float budget,float base){ G.setColor(GREY); font(fitSize(s,budget-W*0.010f,H*0.042f),false); text(s,cx,base,1); }
    static String fmt(float val,int dec){ return dec==0?String.valueOf(Math.round(val)):String.format("%."+dec+"f",val); }
    static void glow(Shape s,Color col,float w){
        int[] a={0x14,0x24,0x48,0xFF}; float[] m={1.9f,1.15f,0.62f,0.26f};
        for(int i=0;i<4;i++){
            G.setColor(al(col,a[i]));
            G.setStroke(new BasicStroke(w*m[i],BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            G.draw(s);
        }
    }
    static String tyreLabel(int i){
        if(cjk){ switch(i){ case 0: return "左前胎壓"; case 1: return "右前胎壓";
                            case 2: return "左後胎壓"; default: return "右後胎壓"; } }
        switch(i){ case 0: return "FRONT LEFT"; case 1: return "FRONT RIGHT";
                   case 2: return "REAR LEFT"; default: return "REAR RIGHT"; }
    }
    static String gearText(){
        if(!h(GEAR)) return "--";
        int x=(int)(g(GEAR)+0.5f);
        switch(x){ case 1: return "P"; case 2: return "R"; case 3: return "N"; case 4: return "D"; }
        if(x>=16&&x<=22) return "M"+(x-15);
        return "--";
    }
    static float clamp1(float x){ return x<-1f?-1f:x>1f?1f:x; }

    static BufferedImage carArt(){
        String[] tries={"car.png","../../assets/car.png","assets/car.png"};
        for(String t:tries){ File f=new File(t);
            if(f.isFile()){ try{ return ImageIO.read(f); }catch(Exception e){} } }
        return null;
    }
    static void drawCar(){
        BufferedImage art=carArt();
        if(art!=null){
            float sw=art.getWidth(), sh=art.getHeight();
            float scale=Math.min(carW/sw,carH/sh);
            float w=sw*scale, hgt=sh*scale;
            G.drawImage(art,Math.round(carCx-w/2),Math.round(carCy-hgt/2),Math.round(w),Math.round(hgt),null);
            return;
        }
        drawCarFallback();
    }
    static void drawCarFallback(){
        float hw=carW*0.5f,hh=carH*0.5f,x=carCx,y=carCy;
        GeneralPath p=new GeneralPath();
        p.moveTo(x-hw,y+hh*0.50f); p.lineTo(x-hw,y-hh*0.16f);
        p.quadTo(x-hw*0.95f,y-hh*0.50f,x-hw*0.60f,y-hh*0.60f);
        p.lineTo(x-hw*0.42f,y-hh*0.93f);
        p.quadTo(x,y-hh*1.04f,x+hw*0.42f,y-hh*0.93f);
        p.lineTo(x+hw*0.60f,y-hh*0.60f);
        p.quadTo(x+hw*0.95f,y-hh*0.50f,x+hw,y-hh*0.16f);
        p.lineTo(x+hw,y+hh*0.50f);
        p.quadTo(x+hw*0.88f,y+hh*0.74f,x+hw*0.52f,y+hh*0.76f);
        p.lineTo(x-hw*0.52f,y+hh*0.76f);
        p.quadTo(x-hw*0.88f,y+hh*0.74f,x-hw,y+hh*0.50f); p.closePath();
        p.moveTo(x-hw*0.50f,y-hh*0.58f); p.quadTo(x,y-hh*0.70f,x+hw*0.50f,y-hh*0.58f);
        p.lineTo(x+hw*0.60f,y-hh*0.20f); p.lineTo(x-hw*0.60f,y-hh*0.20f); p.closePath();
        p.append(new Rectangle2D.Float(x-hw*0.92f,y+hh*0.00f,hw*0.48f,hh*0.20f),false);
        p.append(new Rectangle2D.Float(x+hw*0.44f,y+hh*0.00f,hw*0.48f,hh*0.20f),false);
        p.append(new Rectangle2D.Float(x-hw*0.28f,y+hh*0.50f,hw*0.56f,hh*0.14f),false);
        glow(p,CYAN,carW*0.030f);
    }

    static void drawStatic(){
        backdrop(); arcs(); pool(); drawCar();
        columnScale(rpmX+barW,false,6); columnScale(cooX,true,4);
        frame(rpmX,rpmY0,rpmX+barW,rpmY1); frame(cooX,rpmY0,cooX+barW,rpmY1);
        headerCell(pedalBox,cjk?"踏板":"PEDALS");
        headerCell(gearBox, cjk?"檔位":"GEAR");
        headerCell(steerBox,cjk?"轉向角":"STEERING");
        headerCell(coolBox, cjk?"水溫":"COOLANT");

        for(int i=0;i<4;i++){
            Rectangle2D.Float b=tyreBox[i]; boolean left=(i==0||i==2);
            float ax=left?(float)b.getMaxX():(float)b.getMinX(), ay=(float)b.getCenterY();
            G.setColor(c(0x333FD2FF)); G.setStroke(new BasicStroke(3.5f));
            G.draw(new Line2D.Float(ax,ay,tdx[i],tdy[i]));
            G.setColor(CYAN_DIM); G.setStroke(new BasicStroke(1.3f));
            G.draw(new Line2D.Float(ax,ay,tdx[i],tdy[i]));
            float mx=(ax+tdx[i])*0.5f, my=(ay+tdy[i])*0.5f;
            G.setColor(c(0x443FD2FF)); G.fill(new Ellipse2D.Float(mx-2.6f,my-2.6f,5.2f,5.2f));
            G.setColor(c(0x553FD2FF)); G.fill(new Ellipse2D.Float(tdx[i]-6.5f,tdy[i]-6.5f,13f,13f));
            G.setColor(CYAN); G.fill(new Ellipse2D.Float(tdx[i]-3f,tdy[i]-3f,6f,6f));
            frame(b);
            G.setColor(GREY);
            font(fitSize(tyreLabel(i),(float)b.getWidth()-W*0.050f,H*0.038f),false);
            text(tyreLabel(i),(float)b.getMinX()+W*0.012f,(float)b.getMinY()+H*0.038f,0);
        }
        frame(statusBar);
        frame(speedBox);
        label(cjk?"車速 km/h":"SPEED km/h",(float)speedBox.getCenterX(),
              (float)speedBox.getWidth(),(float)speedBox.getMinY()-H*0.018f);
    }

    static void segment(float l,float t,float r,float b,boolean lit,Color col){
        if(lit){ rectF(l-2.5f,t-2.5f,r+2.5f,b+2.5f,al(col,0x55)); rectF(l,t,r,b,col); }
        else rectF(l,t,r,b,al(col,0x1E));
    }
    static void drawLive(){
        final int HALF=12; float inner=barW*0.18f, x0=rpmX+inner, x1=rpmX+barW-inner;
        float span=(rpmY1-rpmY0)-inner*2f, mid=rpmY0+inner+span*0.5f, seg=span*0.5f/HALF;
        float pthr=h(ACCEL)?Math.max(0,Math.min(1,g(ACCEL)/ACCEL_FULL)):0f;
        float pbrk=h(BRAKE)?Math.max(0,Math.min(1,g(BRAKE)/BRAKE_FULL)):0f;
        int onUp=(int)(pthr*HALF+0.5f), onDn=(int)(pbrk*HALF+0.5f);
        for(int i=0;i<HALF;i++){
            float pad=seg*0.14f;
            segment(x0,mid-(i+1)*seg+pad,x1,mid-i*seg-pad,i<onUp,CYAN);
            segment(x0,mid+i*seg+pad,x1,mid+(i+1)*seg-pad,i<onDn,RED);
        }
        rectF(rpmX-1f,mid-1.2f,rpmX+barW+1f,mid+1.2f,c(0xCCEAF6FF));
        if(peakThr>0.03f){ float y=mid-peakThr*span*0.5f; rectF(rpmX-2f,y-1.5f,rpmX+barW+2f,y+1.5f,CYAN); }
        if(peakBrk>0.03f){ float y=mid+peakBrk*span*0.5f; rectF(rpmX-2f,y-1.5f,rpmX+barW+2f,y+1.5f,RED); }
        boolean braking=h(BRAKE)&&g(BRAKE)>PEDAL_DEAD;
        boolean onGas=!braking&&h(ACCEL)&&g(ACCEL)>PEDAL_DEAD;
        int live=braking?BRAKE:ACCEL;
        G.setColor(!h(live)?GREY:braking?RED:onGas?CYAN:WHITE); font(H*0.090f,true);
        num(h(live)?fmt(g(live),0):"--",(float)pedalBox.getCenterX(),H*0.1583f,1);
        if(braking||onGas){ String dir=braking?(cjk?"刹車":"BRAKE"):(cjk?"加速":"THROTTLE");
            G.setColor(braking?RED:CYAN);
            font(fitSize(dir,(float)pedalBox.getWidth(),H*0.036f),false);
            text(dir,(float)pedalBox.getCenterX(),H*0.201f,1); }
        // coolant
        float ix=barW*0.18f,cx0=cooX+ix,cx1=cooX+barW-ix,cy0=rpmY0+ix,cy1=rpmY1-ix;
        float cf=h(COOLANT)?(g(COOLANT)-COOLANT_MIN)/(COOLANT_MAX-COOLANT_MIN):0f;
        cf=Math.max(0,Math.min(1,cf)); float top=cy1-(cy1-cy0)*cf;
        boolean hot=h(COOLANT)&&g(COOLANT)>=COOLANT_WARN, cold=h(COOLANT)&&g(COOLANT)<COOLANT_COLD;
        Color col=hot?RED:cold?AMBER:CYAN;
        rectF(cx0,cy0,cx1,cy1,c(0xFF10202B));
        if(h(COOLANT)&&cf>0.01f){
            rectF(cx0,top,cx1,cy1,al(col,hot?(int)(0x50+0x50*pulse):0x66));
            G.setColor(col); G.setStroke(new BasicStroke(2.4f));
            GeneralPath w=new GeneralPath(); w.moveTo(cx0,top); w.quadTo((cx0+cx1)*0.5f,top-4,cx1,top); G.draw(w);
        }
        G.setColor(h(COOLANT)?(hot?RED:cold?AMBER:WHITE):GREY); font(H*0.062f,true);
        num(h(COOLANT)?fmt(g(COOLANT),0):"--",(float)coolBox.getCenterX(),H*0.1583f,1);
        if(cold){ String warm=cjk?"暖車中":"WARMING";
                  G.setColor(al(AMBER,(int)(0x80+0x7F*pulse)));
                  font(fitSize(warm,(float)coolBox.getWidth(),H*0.036f),false);
                  text(warm,(float)coolBox.getCenterX(),H*0.201f,1); }
        // gear
        if(gearFlash) rectF((float)gearBox.getMinX(),(float)gearBox.getMinY(),(float)gearBox.getMaxX(),(float)gearBox.getMaxY(),c(0x333FD2FF));
        G.setColor(h(GEAR)?(gearFlash?WHITE:CYAN):GREY); font(H*0.105f,true);
        num(gearText(),(float)gearBox.getCenterX(),H*0.1583f,1);
        // steering
        float deg=g(STEER);
        G.setColor(h(STEER)?WHITE:GREY); font(H*0.095f,true);
        num(h(STEER)?fmt(deg,0):"--",(float)steerBox.getCenterX(),H*0.1583f,1);
        // tyres
        for(int i=0;i<4;i++){
            Rectangle2D.Float b=tyreBox[i]; int t=tyreType[i];
            boolean ok=h(t); float psi=g(t);
            boolean acq=ok&&psi<TPMS_PRESENT;
            boolean bad=ok&&!acq&&(psi<TPMS_LOW||psi>TPMS_HIGH);
            Color cc=(!ok||acq)?GREY:bad?AMBER:WHITE;
            if(bad) rectF((float)b.getMinX(),(float)b.getMinY(),(float)b.getMaxX(),(float)b.getMaxY(),al(AMBER,(int)(0x14+0x1C*pulse)));
            float nx=(float)b.getMinX()+W*0.012f, ny=(float)b.getMaxY()-H*0.018f;
            if(acq){ String wait=cjk?"偵測中":"ACQUIRING"; G.setColor(GREY);
                     font(fitSize(wait,(float)b.getWidth()-W*0.030f,H*0.050f),false); text(wait,nx,ny,0); }
            else {
                G.setColor(cc); font(H*0.070f,true);
                String val=ok?fmt(psi,1):"--";
                float nw=numWidth(val,G.getFont().getSize2D());
                num(val,nx,ny,0);
                G.setColor(GREY); font(H*0.040f,false); text("PSI",nx+nw+W*0.012f,ny,0);
            }
            G.setColor((!ok||acq)?GREY:bad?AMBER:GREEN);
            float rr=H*0.013f;
            G.fill(new Ellipse2D.Float((float)b.getMaxX()-W*0.018f-rr,(float)b.getMinY()+H*0.030f-rr,rr*2,rr*2));
        }
        // friction circle
        boolean gok=h(G_LAT)||h(G_LONG);
        G.setColor(CYAN_DIM); G.setStroke(new BasicStroke(1.4f));
        G.draw(new Ellipse2D.Float(gCx-gR,gCy-gR,gR*2,gR*2));
        G.draw(new Ellipse2D.Float(gCx-gR*0.5f,gCy-gR*0.5f,gR,gR));
        G.draw(new Line2D.Float(gCx-gR,gCy,gCx+gR,gCy)); G.draw(new Line2D.Float(gCx,gCy-gR,gCx,gCy+gR));
        if(gok){
            for(int i=trailN-1;i>0;i--){
                float a=1f-(float)i/TRAIL; float rad=1.5f+2.2f*a;
                G.setColor(al(CYAN,(int)(0x10+0x55*a*a)));
                G.fill(new Ellipse2D.Float(gCx+clamp1(trailX[i])*gR-rad,gCy+clamp1(trailY[i])*gR-rad,rad*2,rad*2));
            }
            G.setColor(WHITE);
            G.fill(new Ellipse2D.Float(gCx+clamp1(trailX[0])*gR-4.2f,gCy+clamp1(trailY[0])*gR-4.2f,8.4f,8.4f));
        }
        float lx=gCx+gR+W*0.018f;
        G.setColor(GREY); font(H*0.036f,false); text(cjk?"G 最大":"PEAK G",lx,gCy-H*0.010f,0);
        G.setColor(gok?CYAN:GREY); font(H*0.052f,true);
        num(gok?fmt(maxG,2):"--",lx,gCy+H*0.052f,0);
        drawHeading();
        drawStatus();
        // speed
        G.setColor(h(SPEED)?WHITE:GREY); font(H*0.130f,true);
        num(h(SPEED)?fmt(g(SPEED),0):"--",(float)speedBox.getCenterX(),H*0.930f,1);
    }

    static float fitSize(String t,float room,float want){
        G.setFont(new Font(FONT,Font.PLAIN,Math.round(want)));
        float w=tw(t);
        if(w<=room||w<=0) return want;
        float sc=want*room/w;
        return sc<want*0.55f?want*0.55f:sc;
    }
    static float gMag(){ if(!h(G_LAT)&&!h(G_LONG)) return 0f;
        float a=g(G_LAT),b=g(G_LONG); return (float)Math.sqrt(a*a+b*b); }
    static String wheelShort(int i){
        if(cjk){ switch(i){ case 0: return "左前"; case 1: return "右前"; case 2: return "左後"; default: return "右後"; } }
        switch(i){ case 0: return "FL"; case 1: return "FR"; case 2: return "RL"; default: return "RR"; }
    }
    static void drawStatus(){
        String head, detail; Color col; int worst=-1; boolean anyAcq=false;
        if(h(COOLANT)&&g(COOLANT)>=COOLANT_WARN){
            head=cjk?"水溫過高":"COOLANT HOT"; detail=fmt(g(COOLANT),0)+" C"; col=RED;
        } else {
            for(int i=0;i<4;i++){ int t=tyreType[i]; if(!h(t)) continue;
                if(g(t)<TPMS_PRESENT){ anyAcq=true; continue; }
                if(g(t)<TPMS_LOW||g(t)>TPMS_HIGH){ worst=i; break; } }
            float gm=gMag();
            if(worst>=0){ boolean low=g(tyreType[worst])<TPMS_LOW;
                head=cjk?(low?"胎壓過低":"胎壓過高"):(low?"TYRE LOW":"TYRE HIGH");
                detail=wheelShort(worst)+" "+fmt(g(tyreType[worst]),1); col=AMBER;
            } else if(gm>=G_ALERT){ head=cjk?"G 值偏高":"HIGH G"; detail=fmt(gm,2)+" g"; col=AMBER;
            } else if(h(COOLANT)&&g(COOLANT)<COOLANT_COLD){ head=cjk?"暖車中":"WARMING"; detail=fmt(g(COOLANT),0)+" C"; col=AMBER;
            } else if(anyAcq){ head=cjk?"胎壓偵測中":"TPMS WAIT"; detail=cjk?"等待感測器":"no sensor yet"; col=GREY;
            } else { head=cjk?"狀態正常":"ALL OK"; detail=""; col=GREEN; }
        }
        if(col==RED||col==AMBER)
            rectF((float)statusBar.getMinX(),(float)statusBar.getMinY(),(float)statusBar.getMaxX(),(float)statusBar.getMaxY(),al(col,(int)(0x16+0x22*pulse)));
        float room=(float)statusBar.getWidth()-W*0.020f;
        boolean hasD=detail.length()>0; float gap=hasD?W*0.018f:0f;
        float hs=fitSize(head,hasD?room*0.64f:room,H*0.055f);
        font(hs,false); float hw=tw(head);
        float ds=0f,dw=0f;
        if(hasD){ ds=fitSize(detail,room-hw-gap,H*0.038f); font(ds,false); dw=tw(detail); }
        float sx=(float)statusBar.getCenterX()-(hw+gap+dw)*0.5f;
        G.setColor(col); font(hs,false); text(head,sx,H*0.898f,0);
        if(hasD){ G.setColor(GREY); font(ds,false); text(detail,sx+hw+gap,H*0.898f,0); }
    }
    static void drawHeading(){
        if(!h(STEER)&&!h(SPEED)) return;
        float t=h(STEER)?clamp1(g(STEER)/STEER_FULL):0f;
        float spd=h(SPEED)?Math.max(0,Math.min(1,g(SPEED)/120f)):0f;
        float baseX=carCx, baseY=carCy-carH*0.52f;
        float len=carH*(0.17f+0.14f*spd);
        float tipX=baseX+t*carW*0.44f, tipY=baseY-len;
        float ctrlX=baseX+t*carW*0.16f, ctrlY=baseY-len*0.55f;
        int alpha=(int)(0x66+0x99*spd);
        GeneralPath sh=new GeneralPath();
        sh.moveTo(baseX,baseY); sh.quadTo(ctrlX,ctrlY,tipX,tipY);
        G.setColor(al(CYAN,alpha/3));
        G.setStroke(new BasicStroke(carW*0.100f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND)); G.draw(sh);
        G.setColor(al(CYAN,alpha));
        G.setStroke(new BasicStroke(carW*0.038f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND)); G.draw(sh);
        float dx=tipX-ctrlX, dy=tipY-ctrlY;
        float m=(float)Math.sqrt(dx*dx+dy*dy); if(m<0.001f) return;
        dx/=m; dy/=m; float px=-dy, py=dx;
        float hl=carH*0.090f, hw=carW*0.100f;
        GeneralPath hd=new GeneralPath();
        hd.moveTo(tipX+dx*hl,tipY+dy*hl); hd.lineTo(tipX+px*hw,tipY+py*hw);
        hd.lineTo(tipX-px*hw,tipY-py*hw); hd.closePath();
        G.setColor(al(CYAN,alpha/3)); G.fill(hd);
        G.setColor(al(CYAN,alpha)); G.setStroke(new BasicStroke(2.2f)); G.draw(hd);
    }

    static void seedTrail(float lat,float lon,boolean corner){
        trailN=TRAIL;
        for(int i=0;i<TRAIL;i++){
            float u=i/(float)TRAIL;
            if(corner){ double a=Math.PI*0.55-u*1.5; float rad=(float)(0.62-u*0.30);
                        trailX[i]=(float)(Math.cos(a)*rad); trailY[i]=(float)(-Math.sin(a)*rad); }
            else { trailX[i]=lat*(1-u*0.85f); trailY[i]=lon*(1-u*0.85f); }
        }
        trailX[0]=lat; trailY[0]=lon;
    }

    public static void main(String[] a) throws Exception {
        String mode = a.length>0?a[0]:"normal";
        cjk = !(a.length>1 && a[1].equals("en"));
        String fontPath = a.length>2 ? a[2] : null;
        if(fontPath!=null && new File(fontPath).isFile()){
            numFont=Font.createFont(Font.TRUETYPE_FONT,new File(fontPath));
            measureCells(numFont);
        } else {
            measureCells(new Font("monospaced",Font.PLAIN,100));
        }
        layout();
        if(mode.equals("warn")){
            pulse=1f; peakThr=0.95f; peakBrk=0.42f; maxG=0.94f; gearFlash=false;
            set(RPM,0f); set(COOLANT,109f); set(SPEED,118f); set(GEAR,21f);
            set(ACCEL,0f); set(BRAKE,62f); set(STEER,-148f);
            set(G_LAT,-0.35f); set(G_LONG,0.88f); seedTrail(-0.35f,0.88f,true);
            set(TP_FL,39.2f); set(TP_FR,39.2f); set(TP_RL,38.5f); set(TP_RR,27.5f);
        } else if(mode.equals("ev")){
            pulse=1f; peakThr=0.30f; peakBrk=0.22f; maxG=0.18f; gearFlash=false;
            set(RPM,0f); set(COOLANT,52f); set(SPEED,31f); set(GEAR,4f);
            set(ACCEL,12f); set(BRAKE,0f); set(STEER,-4f);
            set(G_LAT,0.05f); set(G_LONG,-0.03f); seedTrail(0.05f,-0.03f,false);
            set(TP_FL,39.2f); set(TP_FR,39.2f); set(TP_RL,38.5f); set(TP_RR,38.2f);
        } else if(mode.equals("lock")){
            // worst case for the header: full lock at a crawl, when the arrow swings widest
            pulse=1f; peakThr=0.40f; peakBrk=0.95f; maxG=0.22f;
            set(RPM,0f); set(COOLANT,88f); set(SPEED,4f); set(GEAR,2f);
            set(ACCEL,26f); set(BRAKE,0f); set(STEER,390f);   // right lock: the panel is on this side
            set(G_LAT,0.10f); set(G_LONG,0.04f); seedTrail(0.10f,0.04f,false);
            set(TP_FL,39.2f); set(TP_FR,39.2f); set(TP_RL,38.5f); set(TP_RR,38.2f);
        } else if(mode.equals("acq")){
            pulse=1f; peakThr=0.55f; peakBrk=0.30f; maxG=0.31f; gearFlash=false;
            set(RPM,0f); set(COOLANT,88f); set(SPEED,48f); set(GEAR,4f);
            set(ACCEL,18f); set(BRAKE,0f); set(STEER,6f);
            set(G_LAT,0.08f); set(G_LONG,-0.05f); seedTrail(0.08f,-0.05f,false);
            set(TP_FL,39.2f); set(TP_FR,0f); set(TP_RL,38.5f); set(TP_RR,0f);
        } else {
            pulse=0.6f; peakThr=0.66f; peakBrk=0.48f; maxG=0.47f; gearFlash=false;
            set(RPM,0f); set(COOLANT,88f); set(SPEED,64f); set(GEAR,4f);
            set(ACCEL,34f); set(BRAKE,0f); set(STEER,12f);
            set(G_LAT,0.32f); set(G_LONG,-0.18f); seedTrail(0.32f,-0.18f,true);
            set(TP_FL,39.2f); set(TP_FR,39.2f); set(TP_RL,38.5f); set(TP_RR,38.2f);
        }
        BufferedImage img=new BufferedImage(800,480,BufferedImage.TYPE_INT_RGB);
        G=img.createGraphics();
        G.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        G.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        G.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
        drawStatic(); drawLive();
        String tag = fontPath==null?"":"-"+new File(fontPath).getName().replace(".ttf","");
        String name="dash-"+mode+tag+(cjk?"":"-en")+".png";
        ImageIO.write(img,"png",new File(name));
        System.out.println("wrote "+name);
    }
}
