import static java.lang.Math.abs;
import static java.lang.Math.hypot;
import static java.lang.System.out;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileFilter;

public class Canary {
    
    public static final byte RADIUS = 3, // == STEP / 2
                               STEP = 7,
                           IMG_STEP = 7;
    public static final short IMG_WIDTH = 528;
    public static final int[] COVERAGE = new int[] {Color.LIGHT_GRAY.getRGB(),Color.GRAY.getRGB()};
    public static final int SHOT = Color.BLACK.getRGB(),
                            MISS = Color.YELLOW.getRGB(),
                            BACKGROUND = Color.WHITE.getRGB(),
                            FOREGROUND = Color.BLACK.getRGB(); 
    public static boolean[][] MAP = null;
    
    /**
     * A queue of objects which hold the coordinates and required
     * propulsion for each shot.
     */
    private static final class Shot {
        private static final double a = .49f,
                                    b = .51f,
                                    v  = .25 - 1.111853393488571e-3;
        /**
         * @param r Distance to target.
         * @param r0 Initial distance.
         * @return Amount of required propulsion TNT. 
         */
        private static final short propulsion(final short r,final double r0) {
            final double dv = r + .5 - r0;
            final short nTnT = (short)(dv / v);
            return (short)(nTnT + (abs(dv % 1 - .5) < abs((dv + v + r0) % 1 - .5)? 0 : 1));
        }
        
        /**
         * @param i First axis distance.
         * @param ni First axis inverse distance.
         * @param j Second axis distance.
         * @param nj Second axis inverse distance.
         * @return An array of coordinate pairs representing the amount of tnt
         *         needed to hit this target from each corner and parity.
         */
        private static final short[][] propulsion(final short i,final short ni,
                                                  final short j,final short nj) {
            final short xbn = propulsion(ni,b),xbp = propulsion(i,b),
                        xan = propulsion(ni,a),xap = propulsion(i,a),
                        zbn = propulsion(nj,b),zbp = propulsion(j,b),
                        zan = propulsion(nj,a),zap = propulsion(j,a);
            return new short[][] {new short[] {xbn,zan}, // se
                                  new short[] {xap,zbn}, // sw
                                  new short[] {xan,zbp}, // ne
                                  new short[] {xbp,zap}, // nw
                                  
                                  new short[] {xan,zbn}, // se
                                  new short[] {xbp,zan}, // sw
                                  new short[] {xbn,zap}, // ne
                                  new short[] {xap,zbp}}; // nw
        }
        
        public final short r,c;
        public final short[][] propulsion;
        public Shot next;
        
        /**
         * @param r Row
         * @param c Column
         * @param next Next shot, if applicable.
         */
        public Shot(final short r,final short c,final Shot next) {
            propulsion = propulsion(this.r = r,(short)(IMG_WIDTH - r),
                                    this.c = c,(short)(IMG_WIDTH - c));
            this.next = next;
        }
        /**
         * @param s Shot to clone.
         */
        public Shot(final Shot s) {r = s.r; c = s.c; propulsion = s.propulsion; next = s.next == null? null : new Shot(s.next);}
        
        /**
         * Breaks the link between this shot and the next.
         * 
         * @return The next shot in the queue.
         */
        public Shot pop() {final Shot out = next; next = null; return out;}
        /**
         * Pushes a shot to the end of the queue.
         * @param s
         */
        public void push(final Shot s) {if(next == null) next = s; else next.push(s);}
    }
    
    /**
     * @param r Row.
     * @param nc Column.
     * @return <code>true</code> if the region can be safely bombed.
     */
    private static final boolean scan(final short r,final short nc) {
        if(nc >= IMG_WIDTH || nc < 0) return false;
        for(short nr = (short)(r - RADIUS);nr <= r + RADIUS;++nr)
            if(!MAP[nr][nc]) return false;
        return true;
    }
    /**@see #scan*/
    private static final boolean scanEast(final short r,final short c) {return scan(r,(short)(c + RADIUS));}
    /**@see #scan*/
    private static final boolean scanWest(final short r,final short c) {return scan(r,(short)(c - RADIUS));}
    
    /**
     * @param r Row.
     * @param c Column.
     * 
     * @return The first position where the first shot can be placed, or <code>-1</code>
     *         if no such location exists.
     */
    private static final short firstFitEast(final short r,final short c) {
        if(r >= IMG_WIDTH - RADIUS) return -1;
        byte ctr = STEP;
        short nc = c;
        // Continuously iterate until a contiguous region with a 'STEP'^2 area is found
        // or the edge of the image is found.
        while(ctr > 0 && nc < IMG_WIDTH) {
            if(scan(r,nc)) --ctr;
            else ctr = STEP;
            ++nc;
        }
        return (short)(ctr == 0? nc - RADIUS - 1 : -1);
    }
    /**
     * @param r Row.
     * @param c Column.
     * 
     * @return The first position where the first shot can be placed, or <code>-1</code>
     *         if no such location exists.
     */
    private static final short firstFitWest(final short r,final short c) {
        if(r < RADIUS) return -1;
        byte ctr = STEP;
        short nc = c;
        // Continuously iterate until a contiguous region with a 'STEP'^2 area is found
        // or the edge of the image is found.
        while(ctr > 0 && nc > 0) {
            if(scan(r,nc)) --ctr;
            else ctr = STEP;
            --nc;
        }
        return (short)(ctr == 0? nc + RADIUS + 1 : -1);
    }
    
    /**
     * @param r Row.
     * @param c Column.
     * 
     * @return The next position where a shot can be placed, or <code>-1</code>
     *         if no such location exists.
     */
    private static final short nextEast(final short r,final short c) {
        if(c >= IMG_WIDTH - RADIUS || !scanEast(r,(short)(c + 1))) return -1;
        short i = 2;
        // Keep scanning until either a shot would go out of bounds or form a gap in
        // shot coverage.
        while(i <= STEP && scanEast(r,(short)(c + i))) ++i;
        return (short)(i - 1);
    }
    /**
     * @param r Row.
     * @param c Column.
     * 
     * @return The next position where a shot can be placed, or <code>-1</code>
     *         if no such location exists.
     */
    private static final short nextWest(final short r,final short c) {
        if(c < RADIUS || !scanWest(r,(short)(c - 1))) return -1;
        short i = 2;
        // Keep scanning until either a shot would go out of bounds or form a gap in
        // shot coverage.
        while(i <= STEP && scanWest(r,(short)(c - i))) ++i;
        return (short)(i - 1);
    }
    
    /**
     * @param r Row.
     * 
     * @return Gets an entire line of shots.
     */
    private static final Shot getLineEast(final short r) {
        Shot out = null;
        short i = 0,j = -1;
        // Find a section of shots for each discontinuous portion on the line.
        while((i = firstFitEast(r,(short)(j + 1))) >= 0) {
            j = 0;
            // Place each shot and move on to the next.
            do out = new Shot(r,j += i,out);
            while((i = nextEast(r,j)) > 0);
        }
        return out;
    }
    /**
     * @param r Row.
     * 
     * @return Gets an entire line of shots.
     */
    private static final Shot getLineWest(final short r) {
        Shot out = null;
        short i = 0,j = IMG_WIDTH;
        // Find a section of shots for each discontinuous portion on the line.
        while((i = firstFitWest(r,(short)(j - 1))) >= 0) {
            j = (short)(2 * i);
            // Place each shot and move on to the next.
            do out = new Shot(r,j -= i,out);
            while((i = nextWest(r,j)) > 0);
        }
        return out;
    }
    
    /**
     * @param offset Row offset.
     * 
     * @return All shots.
     */
    private static final Shot getLinesEast(final short offset) {
        Shot out = null;
        for(short r = (short)(offset + RADIUS);r < IMG_WIDTH;r += STEP) {
            if(out == null) out = getLineEast(r);
            else out.push(getLineEast(r));
        }
        return out;
    }
    /**
     * @param offset Row offset.
     * 
     * @return All shots.
     */
    private static final Shot getLinesWest(final short offset) {
        Shot out = null;
        for(short r = (short)(offset + RADIUS);r < IMG_WIDTH;r += STEP) {
            if(out == null) out = getLineWest(r);
            else out.push(getLineWest(r));
        }
        return out;
    }
    
    /**Transfers all the shots which get unique blocks.*/
    private static final void AAHelper(final Shot in,Shot shot,final boolean[][] destroyed) {
        while(shot != null) {
            boolean pass = false;
            for(int r = shot.r - RADIUS;r <= shot.r + RADIUS;++r)
                for(int c = shot.c - RADIUS;c <= shot.c + RADIUS;++c)
                    if(!destroyed[r][c]) {destroyed[r][c] = true; pass = true;}
            if(pass) in.push(shot);
            shot = shot.pop();
        }
    }
    /**
     * @param in Input shots.
     * @param factor Scalar to offset the shots.
     * @param destroyed Damage map.
     * 
     * @return The anti-aliased shot.
     */
    private static final Shot antiAliasEast(final Shot in,final short factor,final boolean[][] destroyed) {
        if(factor < 0) return in;
        
        final short offset1 = (short)(RADIUS + factor),
                    offset2 = (short)(RADIUS - factor);
        final Shot out = new Shot(in);
        {
            final Shot shot = getLinesEast(offset1);
            if(factor > 0) shot.push(getLinesEast(offset2));
            AAHelper(out,shot,destroyed);
        }
        return antiAliasEast(out,(short)(factor - 1),destroyed);
    }
    /**
     * @param in Input shots.
     * @param factor Scalar to offset the shots.
     * @param destroyed Damage map.
     * 
     * @return The anti-aliased shot.
     */
    private static final Shot antiAliasWest(final Shot in,final short factor,final boolean[][] destroyed) {
        if(factor < 0) return in;
        
        final short offset1 = (short)(RADIUS + factor),
                    offset2 = (short)(RADIUS - factor);
        final Shot out = new Shot(in);
        {
            final Shot shot = getLinesWest(offset1);
            if(factor > 0) shot.push(getLinesWest(offset2));
            AAHelper(out,shot,destroyed);
        }
        return antiAliasWest(out,(short)(factor - 1),destroyed);
    }
    
    private static final BufferedImage copyImage(final BufferedImage in) {
        final BufferedImage out = new BufferedImage(in.getWidth(),in.getHeight(),BufferedImage.TYPE_INT_RGB);
        out.getGraphics().drawImage(in,0,0,null);
        return out;
    }
    
    private static final class DrawResult {
        public Shot shot;
        public final boolean[][] damaged;
        public final int ndamage,nmiss,nshots;
        public final double acc,eff,err;
        
        public final byte pidx;
        public final long propulsion;
        public final boolean north;
        public final boolean west;
        public final boolean mirror;
        
        public final String file;
        
        protected DrawResult(final boolean[][] damaged,final int ndamage,final int nmiss,final int nshots,
                             final double acc,final double eff,final Shot shot,final String file) {
            this.damaged = damaged;
            this.ndamage = ndamage;
            this.nmiss = nmiss;
            this.nshots = nshots;
            this.acc = acc;
            this.eff = eff;
            this.shot = shot;
            this.file = file;
            err = getErr(acc,eff,nshots);
            {
                byte midx = 0;
                {
                    // Add up the total propulsion for every shot and determine which
                    // rotation and parity is the most efficient.
                    final long[] prop = new long[8];
                    for(Shot s = shot;s != null;s = s.next) {
                        byte i = -1;
                        for(final short[] p : s.propulsion) prop[++i] += p[0] + p[1];
                    }
                    long min = prop[0];
                    for(byte b = 1;b < 8;++b) if(prop[b] < min) {min = prop[b]; midx = b;}
                    pidx = midx;
                    propulsion = min;
                }
                // Decompose the binary representation of the minimum index into booleans.
                west = (midx & (byte)1) == (byte)1;
                north = ((midx = (byte)(midx >> (byte)1)) & (byte)1) == (byte)1;
                mirror = ((midx = (byte)(midx >> (byte)1)) & (byte)1) == (byte)1;
            }
        }
        
        private static final double getErr(final double acc,final double eff,final int nshots)
        {return hypot(100.0 - acc,100.0 - eff);}
    }
    
    /**Prints statistics for the draw result.*/
    private static void printResult(final String title,final DrawResult dr) {
        out.println(title+": "+dr.file);
        out.println("\t#  dmg:"+dr.ndamage);
        out.println("\t# fail:"+dr.nmiss);
        out.println("\t#  tnt:"+dr.nshots);
        out.println("\t%  acc:"+dr.acc);
        out.println("\t%  eff:"+dr.eff);
        out.println("\t   err:"+dr.err);
        out.println("\t# prop:"+dr.propulsion);
        out.print  ("\t place:");
        {
            final StringBuilder sb = new StringBuilder(dr.mirror? "mirrored ":"")
                                               .append(dr.north? "north":"south").append(' ')
                                               .append(dr.west? "west":"east");
            out.println(sb);
        }
        out.println();
    }
    
    private static final void damage(final BufferedImage g,final int r,final int c) {g.setRGB(c,r,COVERAGE[(r + c) % 2]);}
    
    public static final DrawResult draw(final Shot shots,final BufferedImage original,final String file,
                                        final int total) throws IOException {
        final BufferedImage o = copyImage(original);
        final boolean[][] damaged = new boolean[IMG_WIDTH][IMG_WIDTH];
        
        // For each shot, draw and record all the pixels which were damaged by tnt.
        int ndamage = 0,nshots = 0;
        for(Shot s = shots;s != null;s = s.next) {
            for(int c = s.c - RADIUS;c <= s.c + RADIUS;++c)
                for(int r = s.r - RADIUS;r <= s.r + RADIUS;++r)
                    if(!damaged[r][c]) {damage(o,r,c); ++ndamage; damaged[r][c] = true;}
            ++nshots; o.setRGB(s.c,s.r,SHOT);
        }
        
        // Find any damaged out-of-bounds pixels (should not happen).
        int nmiss = 0;
        for(int r = 0;r < IMG_WIDTH;r++)
            for(int c = 0;c < IMG_WIDTH;c++)
                if(!MAP[r][c] && o.getRGB(c,r) != Color.WHITE.getRGB()) {o.setRGB(c,r,MISS); ++nmiss;}
        
        final DrawResult dr = new DrawResult(
            damaged,
            ndamage,
            nmiss,
            nshots,
            ((double)(ndamage - nmiss) / (double)total) * 100.0,
            ((double)ndamage / (double)(nshots * STEP * STEP)) * 100.0,
            shots,
            file
        );
        final String title;
        {
            final String[] split = file.split(Pattern.quote(File.separator));
            title = split[split.length-1];
        }
        printResult(title,dr);
        
        ImageIO.write(o,"png",new File(file));
        
        return dr;
    }
    
    private static interface Comparator {DrawResult compare(final DrawResult a,final DrawResult b);}
    
    /**Creates the map from an image.*/
    private static final BufferedImage createMap(final String file) throws IOException {
        out.println(file);
        
        final BufferedImage original = ImageIO.read(new File(file));
        
        if(original.getWidth() != IMG_WIDTH && original.getHeight() != IMG_WIDTH)
            throw new IllegalArgumentException("Image size:"+IMG_WIDTH+'x'+IMG_WIDTH+" != "+original.getWidth()+'x'+
                                               original.getHeight());
        
        final BufferedImage out = copyImage(original);
        
        MAP = new boolean[IMG_WIDTH][IMG_WIDTH];
        for(short row = 0;row < IMG_WIDTH;++row)
            for(short col = 0;col < IMG_WIDTH;++col)
                if(MAP[row][col] = out.getRGB(col,row) != BACKGROUND)
                    out.setRGB(col,row,FOREGROUND);
        
        ImageIO.write(out,"png",new File(file.substring(0,file.lastIndexOf('.'))+"map.png"));
        return original;
    }
    
    private static final short[] MODULES = new short[] {1056,528,264,132,66,30,16,8,4,4,2,1};
    /**Decomposes the input propulsion into per-module firing commands.*/
    private static final boolean[] decompose(final short[] in) {
        final boolean[] out = new boolean[MODULES.length * 2];
        final short[] cpy = new short[in.length];
        System.arraycopy(in,0,cpy,0,in.length);
        {
            byte i = 0,j = 0;
            for(;i < MODULES.length && cpy[0] > 0;++i)     if(out[i] = MODULES[i] <= cpy[0]) cpy[0] -= MODULES[i];
            for(;j < MODULES.length && cpy[1] > 0;++i,++j) if(out[i] = MODULES[j] <= cpy[1]) cpy[1] -= MODULES[j];
        }
        return out;
    }
    /**Merge sort helper.*/
    private static final void orderH1(final int[] counts,final byte[] order,final byte start,final byte length) {
        if(length < 2) return;
        // Base case.
        if(length == 2) {
            final byte end = (byte)(start + 1);
            if(counts[order[start]] < counts[order[end]]) {
                final byte x = order[start];
                order[start] = order[end];
                order[end] = x;
            }
            return;
        }
        
        // Divide and conquer.
        final byte l2 = (byte)(length / (byte)2);
        orderH1(counts,order,start,l2);
        final byte s2 = (byte)(start + l2),
                   l3 = (byte)(l2 + (byte)(length % (byte)2));
        orderH1(counts,order,s2,l3);
        
        // Merge.
        final byte s3 = (byte)(l3 + s2);
        for(byte a = start,b = s2;a < b && b < s3;++a) {
            if(counts[order[a]] < counts[order[b]]) {
                byte x = order[b];
                for(byte y = a;y < b;++y) {final byte z = order[y]; order[y] = x; x = z;}
                order[b++] = x;
            }
        }
    }
    /**Compares two shots based on the module order.*/
    private static final boolean compareShots(final Shot a,final Shot b,final byte[] order,final byte pidx) {
        final boolean[] u = decompose(a.propulsion[pidx]),
                        v = decompose(b.propulsion[pidx]);
        for(final byte x : order) if(u[x] == v[x]) return v[x];
        return false;
    }
    /**Merge sort helper.*/
    private static final void orderH2(final Shot[] shots,final byte[] order,final short start,final short length,final byte pidx) {
        if(length < 2) return;
        // Base case.
        if(length == 2) {
            final short end = (short)(start + 1);
            if(compareShots(shots[start],shots[end],order,pidx)) {
                final Shot x = shots[start];
                shots[start] = shots[end];
                shots[end] = x;
            }
            return;
        }
        
        // Divide and conquer.
        final short l2 = (short)(length / (short)2);
        orderH2(shots,order,start,l2,pidx);
        final short s2 = (short)(start + l2),
                    l3 = (short)(l2 + (short)(length % (short)2));
        orderH2(shots,order,s2,l3,pidx);
        
        // Merge.
        final short s3 = (short)(l3 + s2);
        for(short a = start,b = s2;a < b && b < s3;++a) {
            if(compareShots(shots[a],shots[b],order,pidx)) {
                Shot x = shots[b];
                for(short y = a;y < b;++y) {final Shot z = shots[y]; shots[y] = x; x = z;}
                shots[b++] = x;
            }
        }
    }
    /**Re-orders the shots in the draw result based on the best module order.*/
    private static final void order(final DrawResult result) {
        final Shot[] shots;
        {
            final byte[] order;
            {
                final int[] counts = new int[MODULES.length * 2];
                {
                    int shot = -1;
                    shots = new Shot[result.nshots];
                    for(Shot s = result.shot;s != null;s = s.pop()) {
                        shots[++shot] = s;
                        byte i = -1;
                        for(final boolean b : decompose(s.propulsion[result.pidx]))
                        {++i; if(b) ++counts[i];}
                    }
                }
                order = new byte[counts.length];
                for(byte i = 0;i < counts.length;order[i] = i++);
                orderH1(counts,order,(byte)0,(byte)counts.length);
            }
            
            orderH2(shots,order,(short)0,(short)shots.length,result.pidx);
        }
        Shot cursor = result.shot = shots[0];
        for(short i = 1;i < shots.length;cursor = cursor.next = shots[i++]);
    }
    
    private static final char FULL_WIDTH = 0xFEE0;
    /**Makes a string look pretty.*/
    private static final String aestheticize(final String in) {
        final StringBuilder sb = new StringBuilder();
        for(final char c : in.toCharArray()) sb.append((char)(c + (c != ' '? FULL_WIDTH : 0)));
        return sb.toString();
    }
    
    /**An object representing a firing module.*/
    private static final class Module {
        /**A shulker box.*/
        private static final class Box {
            /**An item.*/
            private static final class Item {
                public static final byte MAX_STACK_SIZE = 63;
                public static final String FIRE = "tnt",
                                           BLANK = "ice";
                
                public final boolean fire;
                public byte count = 0;
                private Item(final boolean fire) {this.fire = fire;}
                
                public StringBuilder toNBT()
                {return new StringBuilder("id:").append(fire? FIRE : BLANK).append(",Count:").append(count+1);}
                @Override public String toString() {return new StringBuilder("{").append(toNBT()).append('}').toString();}
            }
            public static final byte BOX_SIZE = 27;
            
            public final Item[] items = new Item[BOX_SIZE];
            public Box next = null;
            private byte slot = 0;
            
            /**Puts an item in the box, creating a new box if necessary.*/
            public Box put(final boolean fire) {
                if(items[slot] == null) items[slot] = new Item(fire);
                else if(items[slot].fire != fire || items[slot].count == Item.MAX_STACK_SIZE) {
                    if(slot == BOX_SIZE - 1) return next = new Box().put(fire);
                    items[++slot] = new Item(fire);
                }
                else ++items[slot].count;
                return this;
            }
            
            public StringBuilder items() {
                if(slot == 0 && items[slot] == null) return null;
                final StringBuilder out = new StringBuilder("{Items:[");
                for(byte i = 0;i <= slot;++i) {
                    if(i > 0) out.append(',');
                    out.append("{Slot:").append(i).append(',').append(items[i].toNBT()).append('}');
                }
                return out.append("]}");
            }
            public StringBuilder toNBT() {
                if(slot == 0 && items[slot] == null) return null;
                return new StringBuilder("id:red_shulker_box,Count:1,tag:{BlockEntityTag:").append(items()).append("}");
            }
            
            public int size() {return 1 + (next == null? 0 : next.size());}
            
            /**Removes any unnecessary items from the box.*/
            public void strip() {while(slot >= 0 && !items[slot].fire) items[slot--] = null;}
        }
        private Box head = new Box();
        private Box cursor = head;
        private Box lastWithFire = null;
        private String moduleID;
        
        public Module(final String moduleID) {this.moduleID = moduleID;}
        
        public void put(final boolean fire) {cursor = cursor.put(fire); if(fire) lastWithFire = cursor;}
        private Box pop() {final Box out = head; head = head.next; out.next = null; return out;}
        /**Removes any unnecessary items/boxes.*/
        private boolean strip() {
            if(lastWithFire == null) return false;
            Box b = lastWithFire;
            while(b != null) {
                final Box c = b.next;
                b.next = null;
                b = c;
            }
            lastWithFire.strip();
            cursor = lastWithFire;
            return true;
        }
        
        private static final String[] SIGN = new String[] {
            "id:oak_sign,"+
            "Count:1,"+
            "tag:{"+
                "display:{"+
                    "Name:\""+
                        "{"+
                            "\\\"text\\\":\\\""+
                                "\\\\u00A7e\\\\u00A7l",
                                
                            "\\\""+
                        "}"+
                    "\""+
                "},"+
                "Enchantments:[{}],"+
                "BlockEntityTag:{"+
                    "Text1:\""+
                        "{"+
                            "\\\"text\\\":\\\""+aestheticize("==========")+"\\\","+
                            "\\\"bold\\\":true,"+
                            "\\\"color\\\":\\\"dark_red\\\","+
                            "\\\"clickEvent\\\":{"+
                                "\\\"action\\\":\\\"run_command\\\","+
                                "\\\"value\\\":\\\""+
                                    "setblock ~ ~ ~-1 chain_command_block{auto:1,Command:\\\\\\\"setblock ~ ~ ~1 air\\\\\\\"}"+
                                "\\\""+
                            "}"+
                        "}"+
                    "\","+
                    "Text2:\""+
                        "{"+
                            "\\\"text\\\":\\\""+aestheticize("Module")+"\\\","+
                            "\\\"bold\\\":true,"+
                            "\\\"color\\\":\\\"black\\\","+
                            "\\\"clickEvent\\\":{"+
                                "\\\"action\\\":\\\"run_command\\\","+
                                "\\\"value\\\":\\\""+
                                    "summon falling_block ~ ~.1 ~ ",
                                    
                                "\\\""+
                            "}"+
                        "}"+
                    "\","+
                    "Text3:\""+
                        "{"+
                            "\\\"text\\\":\\\"",
                            
                            "\\\","+
                            "\\\"bold\\\":true,"+
                            "\\\"color\\\":\\\"black\\\","+
                            "\\\"clickEvent\\\":{"+
                                "\\\"action\\\":\\\"run_command\\\","+
                                "\\\"value\\\":\\\""+
                                    "setblock ~ ~ ~ air"+
                                "\\\""+
                            "}"+
                        "}"+
                    "\","+
                    "Text4:\""+
                        "{"+
                            "\\\"text\\\":\\\""+aestheticize("==========")+"\\\","+
                            "\\\"bold\\\":true,"+
                            "\\\"color\\\":\\\"dark_red\\\""+
                        "}"+
                    "\""+
                "}"+
            "}"
        };
        public static final byte DBL_SIZE = 54,
                                 CHEST_SIZE = 27,
                                 HOPPER_SIZE = 5;
        
        /**Creates a sign-generating command.*/
        private static final StringBuilder sign(final StringBuilder in,final String module) {
            return in == null? null :
                               new StringBuilder(SIGN[0])
                                         .append(aestheticize(module))
                                         .append(SIGN[1])
                                         .append(in)
                                         .append(SIGN[2])
                                         .append(aestheticize(module))
                                         .append(SIGN[3]);
        }
        /**Generates a series of falling command blocks for every command.*/
        private static final String FALLING_0 = "id:falling_block,",
                                    FALLING_1 = "BlockState:{Name:command_block},Time:1,TileEntityData:{auto:1,Command:\\\\\\\"",
                                    FALLING_2 = "\\\\\\\"}",
                                    FALLING_3 = ",Passengers:[{"+FALLING_0+FALLING_1,
                                    FALLING_4 = "}]";
        private static final StringBuilder fallingCommands(final StringBuilder command0,final StringBuilder...commands) {
            final StringBuilder sb = new StringBuilder("{").append(FALLING_1).append(command0).append(FALLING_2);
            final StringBuilder close = new StringBuilder();
            for(final StringBuilder c : commands) {
                sb.append(FALLING_3).append(c).append(FALLING_2);
                close.append(FALLING_4);
            }
            return sb.append(close).append('}');
        }
        
        /**Creates a generic setblock command.*/
        private static final StringBuilder setBlock(final String id,final String[][] properties,final StringBuilder nbt,
                                                    final byte dx,final byte dy) {
            final StringBuilder out = new StringBuilder("setblock ~").append(dx == 0? "" : dx).append(" ~").append(dy == 0? "" : dy).append(" ~ ").append(id);
            if(properties != null) {
                out.append('[');
                boolean notFirst = false;
                for(final String[] s : properties) {
                    if(notFirst) out.append(',');
                    else notFirst = true;
                    out.append(s[0]).append('=').append(s[1]);
                }
                out.append(']');
            }
            return nbt == null? out : out.append(nbt);
        }
        /**Creates a setblock command for the first shulker box.*/
        private static final StringBuilder setShulker(final StringBuilder nbt) {return setBlock("red_shulker_box",new String[][] {new String[] {"facing","west"}},nbt,(byte)1,(byte)0);}
        /**Creates a setblock command for the dispenser.*/
        private static final StringBuilder setDispenser(final StringBuilder sb1) {
            return setBlock(
                "dispenser",
                new String[][] {new String[] {"facing","down"}},
                new StringBuilder("{Items:[{Slot:0,").append(sb1).append("}]}"),
                (byte)1,(byte)1
            );
        }
        /**Creates a setblock command for the first hopper.*/
        private static final StringBuilder setHopper0(final StringBuilder sb2) {
            return setBlock(
                "hopper",
                new String[][] {new String[] {"facing","west"},
                                new String[] {"enabled","false"}},
                new StringBuilder("{Items:[{Slot:0,").append(sb2).append("}]}"),
                (byte)2,(byte)1
            );
        }
        /**Creates a setblock command for the concrete that the torch is placed on.*/
        private static final StringBuilder setConcrete() {return new StringBuilder("setblock ~3 ~1 ~1 white_concrete");}
        /**Creates a setblock command for the redstone torch that powers the first hopper.*/
        private static final StringBuilder setTorch() {return new StringBuilder("setblock ~3 ~1 ~ redstone_wall_torch");}
        private static final byte[] INV_OFFSET = new byte[] {3,2,1,1,2,3};
        /**Creates a setblock command for a generic inventory.*/
        private static final StringBuilder setInv(final int idx,final StringBuilder boxes) {
            final byte nidx = (byte)(idx % 6);
            final boolean hopper = nidx == 2 || nidx == 5;
            return setBlock(
                hopper? "hopper" : "chest",
                hopper? new String[][] {new String[] {"facing",nidx == 2? "east" : "west"}} :
                        new String[][] {new String[] {"type",nidx == 0 || nidx == 4? "right" : "left"}},
                new StringBuilder("{Items:[").append(boxes).append("]}"),
                INV_OFFSET[nidx],(byte)(idx / 3 + (nidx > 3? 1 : 0) + 2)
            );
        }
        /**Creates a command which deletes itself and the chain command block.*/
        private static final StringBuilder cleanup() {return new StringBuilder("fill ~ ~ ~ ~ ~ ~-1 air");}
        /**A simple queue which temporarily holds commands.*/
        private static final class CommandQueue {
            private static final class node {
                private final StringBuilder cmd;
                private node n = null;
                private node(final StringBuilder cmd) {this.cmd = cmd;}
            }
            private node h = null,t = null;
            public int size = 0;
            public CommandQueue push(final StringBuilder cmd) {final node n = new node(cmd); if(++size == 1) t = n; else h.n = n; h = n; return this;}
            public StringBuilder pop() {
                if(size == 0) return null;
                final StringBuilder out = t.cmd;
                {final node n = t.n; t.n = null; t = n;}
                --size; return out;
            }
        }
        /**
         * Formats the NBT of the next shulker box,
         * 
         * @param inv Inventory to fill.
         * @param size Amount of items in the inventory.
         * @param initial <code>true</code> if the first slot is empty.
         */
        private final void getNBT(final StringBuilder inv,final byte size,final boolean initial) {
            if(initial) inv.append("{Slot:0,").append(pop().toNBT()).append('}');
            for(byte s = 1;s < size;++s) inv.append(",{Slot:").append(s).append(',').append(pop().toNBT()).append('}');
        }
        /**
         * Gets a standard double chest. This basically ensures that the double chest
         * is treated as a contiguous inventory instead of two disjoint parts.
         * 
         * @param a First inventory.
         * @param b Second inventory.
         * @param odd <code>true</code> if the delta y is odd.
         */
        private final void getDC(final StringBuilder a,final StringBuilder b,final boolean odd) {getNBT(odd? b : a,CHEST_SIZE,false); getNBT(odd? a : b,CHEST_SIZE,true);}
        /**@return The commands necessary to generate the module.*/
        private final StringBuilder commands() {
            if(!strip()) return null;
            final int nboxes = head.size() - 3;
            
            final CommandQueue q = new CommandQueue().push(setShulker(pop().items()));
            if(head != null) q.push(setDispenser(pop().toNBT())); // Dispenser box
            if(head != null) {// Locked hopper box
                q.push(setConcrete()).push(setTorch());
                q.push(setHopper0(pop().toNBT()));
            }
            if(head != null) { // Remainder boxes
                final int nInv;
                final byte type,nfinal;
                final boolean dbl;
                {
                    final byte group = DBL_SIZE + HOPPER_SIZE;
                    {
                        int m = nboxes % group;
                        
                        // Perfect grouping
                        if(m == 0) {type = 0; nfinal = 0; dbl = false;}
                        // Double chest + partial hopper
                        else if((dbl = m > CHEST_SIZE) && (m -= CHEST_SIZE) > CHEST_SIZE)
                        {type = 2; nfinal = (byte)((m - CHEST_SIZE) % HOPPER_SIZE);}
                        // Single/double chest
                        else {type = 1; nfinal = (byte)(m % CHEST_SIZE);}
                    }
                    final int factor = nboxes / group;
                    nInv = (3 * factor) + (type != 0? type == 1? dbl? 2 : 1 : 3 : 0);
                }
                final StringBuilder[] inv = new StringBuilder[nInv];
                {
                    for(int i = 0;i < nInv;++i) {
                        // Inventory pattern: t f t f t t
                        // 'f' indicates the right half of a chest, which
                        // never gets initialized with a Slot:0.
                        final byte v = (byte)(i % 6);
                        inv[i] = new StringBuilder();
                        // The exception to the pattern is if the last inventory is a
                        // single chest.
                        if(!(v == (byte)1 || v == (byte)3) || (type == (byte)1 && !dbl && i == nInv - 1))
                            {inv[i].append("{Slot:0,").append(pop().toNBT()).append('}');}
                    }
                }
                int y = (nInv / 3) + (nInv % 3 > 0? 1 : 0);
                boolean odd = y % 2 == 1;
                int cursor = nInv;
                if(type == 1) {
                    // If nfinal is zero, then it is actually filled perfectly.
                    final byte nf = nfinal == 0? CHEST_SIZE : nfinal;
                    
                    // The first inventory to fill should be the left-most chest. 'cursor'
                    // will need to be adjusted by one if there are two chests and the
                    // delta y is odd. If there are two chests, then the first should be
                    // filled completely.
                    getNBT(inv[--cursor - (dbl && odd? 1 : 0)],dbl? CHEST_SIZE : nf,false);
                    
                    // If double, then the right chest must be filled with the remainder.
                    if(dbl) getNBT(inv[--cursor + (odd? 1 : 0)],nf,true);
                    
                } else if(type == 2) {
                    // The first inventory must be a hopper.
                    getNBT(inv[--cursor],nfinal,false);
                    
                    getDC(inv[--cursor],inv[--cursor],odd);
                }
                
                if(type != 0) {--y; odd ^= true;}
                
                if(head != null) { // All other boxes
                    // Loop through the delta y groups. The first inventory is always
                    // a hopper. If odd, the next is a chest without a Slot:0.
                    while(y > 0) {
                        // The first inventory must be a hopper.
                        getNBT(inv[--cursor],HOPPER_SIZE,false);
                        
                        getDC(inv[--cursor],inv[--cursor],odd);
                        
                        --y; odd ^= true;
                    }
                }
                
                // If there is only one chest, then the index is manually shifted to account
                // for the first hopper's weird position.
                if(nInv == 1) q.push(setInv(1,inv[0]));
                else while(cursor < nInv) q.push(setInv(cursor,inv[cursor++]));
            }
            q.push(cleanup());
            final StringBuilder c0 = q.pop();
            final StringBuilder[] c = new StringBuilder[q.size];
            for(int i = 0;q.size > 0;++i) c[i] = q.pop();
            return fallingCommands(c0,c);
        }
        
        /**@return The sign to generate this module.*/
        public StringBuilder get() {return sign(commands(),moduleID);}
    }
    
    /**
     * "Does the thing (tm)"
     * 
     * @param file Path of image.
     * @param comparator Function to compare {@linkplain DrawResult}s.
     */
    private static final void execute(final String file,final Comparator comparator) throws IOException {
        /* ==== Optimize the Shot Placement  ==== */
        
        final BufferedImage map = createMap(file);
        
        int toDamage = 0;
        for(final boolean[] r : MAP) for(final boolean c : r) if(c) ++toDamage;
        
        final String nf = file.substring(0,file.lastIndexOf('.'));
        DrawResult best = null;
        
        // Run through the main-sequence lines.
        for(short offset = 0;offset < STEP;offset++) {
            final Shot shotsE = getLinesEast(offset),
                       shotsW = getLinesWest(offset);
            
            DrawResult drE = draw(shotsE,map,nf+offset+"-east.png",toDamage);
            DrawResult drW = draw(shotsW,map,nf+offset+"-west.png",toDamage);
            best = comparator.compare(drW,comparator.compare(drE,best));
            
            final boolean[][] destroyedE = drE.damaged,
                              destroyedW = drW.damaged;
            
            // Copy each destroyed map and play with the anti-aliasing settings
            // to find the most optimal result.
            for(int aa = 0;aa <= RADIUS;aa++) {
                final boolean[][] destroyedECpy = new boolean[IMG_WIDTH][IMG_WIDTH],
                                  destroyedWCpy = new boolean[IMG_WIDTH][IMG_WIDTH];
                for(int i = 0;i < IMG_WIDTH;++i) {
                    System.arraycopy(destroyedE[i],0,destroyedECpy[i],0,IMG_WIDTH);
                    System.arraycopy(destroyedW[i],0,destroyedWCpy[i],0,IMG_WIDTH);
                }
                drE = draw(antiAliasEast(shotsE,RADIUS,destroyedECpy),map,nf+"AA_"+offset+'-'+aa+"-east.png",toDamage);
                drW = draw(antiAliasWest(shotsW,RADIUS,destroyedWCpy),map,nf+"AA_"+offset+'-'+aa+"-west.png",toDamage);
                best = comparator.compare(drE,comparator.compare(drW,best));
            }
        }
        
        printResult("best",best);
        
        /* ==== Generate the Boxes ==== */
        
        order(best);
        final byte l = (byte)(MODULES.length * 2);
        final Module[] modules = new Module[l];
        // Initialize the modules with proper names.
        for(byte i = 0;i < l;++i) modules[i] = new Module((i < MODULES.length? "X" : "Z") + ' ' + String.valueOf(MODULES[i % MODULES.length]));
        
        // Decompose each shot's propulsion and then plug them into the modules.
        for(Shot s = best.shot;s != null;s = s.next) {
            final boolean[] decomp = decompose(s.propulsion[best.pidx]);
            for(byte i = 0;i < l;++i) modules[i].put(decomp[i]);
        }
        // Write the function file.
        try(final BufferedWriter w = Files.newBufferedWriter(Paths.get(nf.substring(0,nf.lastIndexOf(File.separatorChar)),"cmd.mcfunction"))) {
            w.append("give @s chest{display:{Name:\"{\\\"text\\\":\\\"\\\\u00A7e\\\\u00A7l")
             .append(aestheticize("Modules"))
             .append("\\\"}\"},BlockEntityTag:{Items:[");
            byte slot = -1;
            for(final Module m : modules) {
                final StringBuilder sign = m.get();
                if(sign != null)
                    (slot == -1? w : w.append(','))
                                      .append("{Slot:")
                                      .append(String.valueOf(++slot))
                                      .append(',')
                                      .append(sign)
                                      .append('}');
            }
            w.append("]}}");
        } catch(final IOException e) {e.printStackTrace();}
    }
    
    public static void main(final String[] args) {
        final JFileChooser fc = new JFileChooser();
        fc.addChoosableFileFilter(new FileFilter() {
            @Override
            public String getDescription() {return "image file";}
            
            public String getExtension(final File f) {
                String ext = null;
                final String s = f.getName();
                final int i = s.lastIndexOf('.');
                
                if(i > 0 && i < s.length() - 1) ext = s.substring(i + 1).toLowerCase();
                return ext;
            }
            
            @Override
            public boolean accept(File f) {
                if(f.isDirectory()) return true;
                
                final String extension = getExtension(f);
                return (extension != null &&
                       (extension.equals("tiff") || extension.equals("tif") || extension.equals("gif") ||
                        extension.equals("jpeg") || extension.equals("jpg") || extension.equals("png")));
            }
        });
        if(fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) System.exit(0);
        final String file = fc.getSelectedFile().getAbsolutePath();
        try {
            execute(
                file,
                (a,b) -> {
                    if(a == null || b == null) return a == null                  ? b : a;
                    if(a.acc != b.acc)         return a.acc        < b.acc       ? b : a;
                    if(a.nmiss != b.nmiss)     return a.nmiss      > b.nmiss     ? b : a;
                    if(a.err != b.err)         return a.err        > b.err       ? b : a;
                    if(a.eff != b.eff)         return a.eff        < b.eff       ? b : a;
                    if(a.nshots != b.nshots)   return a.nshots     > b.nshots    ? b : a;
                                               return a.propulsion > b.propulsion? b : a;
                }
            );
        } catch(final IOException e) {e.printStackTrace();}
    }
}













































