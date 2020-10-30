import static java.lang.Math.hypot;
import static java.lang.System.out;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.LinkedList;

import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileFilter;

public class ImageProcessor {
	public static int RADIUS = 3; // == STEP / 2
	public static int STEP = 7;
	public static int IMG_WIDTH = 528;
	public static final Color COVERAGE_A = Color.LIGHT_GRAY;
	public static final Color COVERAGE_B = Color.GRAY;
	public static final Color SHOT = Color.BLACK;
	public static final Color MISS = Color.YELLOW;
	public static boolean[][] MAP = null;
	
	private static class Shot {
		int r,c;
		
		Shot(final int r,final int c) {this.r = r; this.c = c;}
	}
	
	private static boolean scan(final int r,final int c) {
		final int nc = c + RADIUS;
		for(int nr = r - RADIUS; nr <= r + RADIUS; nr++) {
			try {if(!MAP[nr][nc]) return false;}
			catch(final ArrayIndexOutOfBoundsException e) {
				System.err.println("("+r+","+c+")->("+nr+","+nc+")");
				e.printStackTrace();
				System.exit(1);
			}
		}
		return true;
	}
	
	private static int firstFit(final int r,final int c) {
		int ctr = STEP;
		int nc = c;
		while(ctr > 0 && nc < IMG_WIDTH) {
			boolean pass = false;
			for(int nr = r - RADIUS; nr <= r + RADIUS && (pass = MAP[nr][nc]); nr++);
			if(pass) ctr--;
			else ctr = STEP;
			nc++;
		}
		return ctr == 0? nc - RADIUS - 1:-1;
	}
	
	private static int next(final int r,final int c) {
		if(c >= IMG_WIDTH-RADIUS || !scan(r,1+c)) return -1;
		int i = 2;
		while(i <= STEP && scan(r,i + c)) i++;
		return i - 1;
	}
	
	private static LinkedList<Shot> getLine(final int r) {
		final LinkedList<Shot> shots = new LinkedList<>();
		// Get first fit.
		int i = 0;
		int j = -1;
		while((i = firstFit(r,j+1)) >= 0) { // While the first shot was found
			j = 0;
			// Get contiguous row of shots.
			do {j += i;shots.add(new Shot(r,j));} while((i = next(r,j)) > 0);
		}
		return shots;
	}
	
	private static BufferedImage createMap(final String file) throws IOException {
		out.println(file);
		final BufferedImage original = ImageIO.read(new File(file));
		final BufferedImage bw = copyImage(original);
		final Graphics g = bw.getGraphics();
		
		if(original.getWidth() != IMG_WIDTH && original.getHeight() != IMG_WIDTH)
			throw new IllegalArgumentException("Image size:" +IMG_WIDTH+'x'+IMG_WIDTH+" != "+original.getWidth()+'x'+original.getHeight());
		
		MAP = new boolean[IMG_WIDTH][IMG_WIDTH];
		for(int row = 0; row < IMG_WIDTH; row++)
			for(int col = 0; col < IMG_WIDTH; col++) {
				MAP[row][col] = original.getRGB(col,row) != Color.WHITE.getRGB();
				g.setColor(MAP[row][col]? Color.BLACK:Color.WHITE);
				g.drawRect(col,row,0,0);
			}
		
		ImageIO.write(bw,"png",new File(file.substring(0,file.lastIndexOf('.'))+"map.png"));
		return original;
	}
	
	private static LinkedList<Shot> getLines(final int offset) {
		final LinkedList<Shot> lines = new LinkedList<>();
		for(int r = offset + RADIUS; r < IMG_WIDTH; r += STEP)
			lines.addAll(getLine(r));
		return lines;
	}
	
	private static LinkedList<Shot> antiAlias(final LinkedList<Shot> in,final int factor,final boolean[][] destroyed) {
		if(factor < 0) return in;
		
		final int offset1 = RADIUS + factor,
				  offset2 = RADIUS - factor;
		
		final LinkedList<Shot> shots = getLines(offset1);
		if(factor > 0) shots.addAll(getLines(offset2));
		for(final Shot s : shots) {
			boolean pass = false;
			for(int r = s.r - RADIUS; r <= s.r + RADIUS; r++)
				for(int c = s.c - RADIUS; c <= s.c + RADIUS; c++)
					if(!destroyed[r][c]) {destroyed[r][c] = true;pass = true;}
			if(pass) in.add(s);
		}
		
		return antiAlias(in,factor-1,destroyed);
	}
	
	private static BufferedImage copyImage(final BufferedImage in) {
		BufferedImage out = new BufferedImage(in.getWidth(),in.getHeight(),BufferedImage.TYPE_INT_RGB);
		out.getGraphics().drawImage(in,0,0,null);
		return out;
	}
	
	private static void damage(final Graphics g,final int r,final int c) {
		g.setColor((r+c)%2 == 0? COVERAGE_A:COVERAGE_B);
		g.drawRect(c,r,0,0);
	}
	
	private static class DrawResult {
		public final boolean[][] damaged;
		public final int ndamage,nmiss,nshots;
		public final double acc,eff,err;
		public final String file;
		protected DrawResult(final boolean[][] damaged,
							 final int ndamage,
							 final int nmiss,
							 final int nshots,
							 final double acc,
							 final double eff,
							 final String file)
			{this.damaged=damaged;
			 this.ndamage=ndamage;
			 this.nmiss=nmiss;
			 this.nshots=nshots;
			 this.acc=acc;
			 this.eff=eff;
			 this.file = file;
			 err = getErr(acc,eff,nshots);}
		
		private static final double getErr(final double acc,final double eff,final int nshots)
			{return hypot(100.0-acc,100.0-eff);}
	}
	
	public static DrawResult draw(final LinkedList<Shot> shots,final BufferedImage original,final String file,final int total) throws IOException {
		final BufferedImage o = copyImage(original);
		final Graphics g = o.getGraphics();
		
		final boolean[][] damaged = new boolean[IMG_WIDTH][IMG_WIDTH];
		
		int ndamage = 0;
		for(final Shot s : shots)
			for(int c = s.c - RADIUS; c <= s.c + RADIUS; c++)
				for(int r = s.r - RADIUS; r <= s.r + RADIUS; r++)
					if(!damaged[r][c]) {damage(g,r,c);ndamage++;damaged[r][c] = true;}
		
		g.setColor(SHOT);
		int nshots = 0;
		for(final Shot s : shots) {
			nshots++;
			g.drawRect(s.c,s.r,0,0);
		}
		
		g.setColor(MISS);
		int nmiss = 0;
		for(int r = 0; r < IMG_WIDTH; r++)
			for(int c = 0; c < IMG_WIDTH; c++)
				if(!MAP[r][c] && o.getRGB(c,r) != Color.WHITE.getRGB())
					{g.drawRect(c,r,0,0);nmiss++;}
		
		final double acc = ((double) (ndamage - nmiss) / (double) total) * 100.0,
					 eff = ((double) ndamage / (double) (nshots * STEP * STEP)) * 100.0;
		out.println(file);
		out.println("\t#  dmg:"+ndamage);
		out.println("\t# fail:"+nmiss);
		out.println("\t#  tnt:"+nshots);
		out.println("\t%  acc:"+acc);
		out.println("\t%  eff:"+eff);
		final DrawResult dr = new DrawResult(damaged,ndamage,nmiss,nshots,acc,eff,file);
		out.println("\t   err:"+dr.err);
		out.println();
		
		ImageIO.write(o,"png",new File(file));
		
		return dr;
	}
	
	private static void printResult(final String title,final DrawResult dr) {
		out.println(title+": "+dr.file);
		out.println("\t#  dmg:"+dr.ndamage);
		out.println("\t# fail:"+dr.nmiss);
		out.println("\t#  tnt:"+dr.nshots);
		out.println("\t%  acc:"+dr.acc);
		out.println("\t%  eff:"+dr.eff);
		out.println("\t   err:"+dr.err);
		out.println();
	}
	
	private static DrawResult compareErr(final DrawResult a, final DrawResult b) {
		if(a == null || b == null) return a == null? b:a;
		if(a.err != b.err) return a.err > b.err? b:a;
		if(a.eff != b.eff) return a.eff > b.eff? a:b;
		if(a.acc != b.acc) return a.acc > b.acc? a:b;
		if(a.nshots != b.nshots) return a.nshots > b.nshots? b:a;
		return a.nmiss > b.nmiss? b:a;
	}
	
	private static DrawResult compareAcc(final DrawResult a, final DrawResult b) {
		if(a == null || b == null) return a == null? b:a;
		if(a.acc != b.acc) return a.acc > b.acc? a:b;
		if(a.nmiss != b.nmiss) return a.nmiss > b.nmiss? b:a;
		if(a.err != b.err) return a.err > b.err? b:a;
		if(a.eff != b.eff) return a.eff > b.eff? a:b;
		return a.nshots > b.nshots? b:a;
	}
	
	private static DrawResult compareEff(final DrawResult a, final DrawResult b) {
		if(a == null || b == null) return a == null? b:a;
		if(a.eff != b.eff) return a.eff > b.eff? a:b;
		if(a.acc != b.acc) return a.acc > b.acc? a:b;
		if(a.err != b.err) return a.err > b.err? b:a;
		if(a.nshots != b.nshots) return a.nshots > b.nshots? b:a;
		return a.nmiss > b.nmiss? b:a;
	}
	
	public static void execute(final String file) throws IOException {
		final BufferedImage original = createMap(file+".png");
		final LinkedList<Shot> total = new LinkedList<>();
		
		int toDamage = 0;
		for(final boolean[] r : MAP)
			for(final boolean c : r)
				if(c) toDamage++;
		
		DrawResult dr_err = null,
				   dr_acc = null,
				   dr_eff = null;
		
		for(int offset = 0; offset < STEP; offset++) {
			final LinkedList<Shot> shots = getLines(offset);
			total.addAll(shots);
			
			String f = file+offset+".png";
			DrawResult dr = draw(shots,original,f,toDamage);
			
			dr_err = compareErr(dr_err,dr);
			dr_acc = compareAcc(dr_acc,dr);
			dr_eff = compareEff(dr_eff,dr);
			
			final boolean[][] destroyed = dr.damaged;
			
			for(int aa = 0; aa <= RADIUS; aa++) {
				final boolean[][] destroyedCpy = new boolean[IMG_WIDTH][IMG_WIDTH];
				for(int i = 0; i < IMG_WIDTH; i++)
					System.arraycopy(destroyed[i],0,destroyedCpy[i],0,IMG_WIDTH);
				f = file+"AA_"+offset+'-'+aa+".png";
				dr = draw(antiAlias(shots,RADIUS,destroyedCpy),original,file+"AA_"+offset+'-'+aa+".png",toDamage);
				
				dr_err = compareErr(dr_err,dr);
				dr_acc = compareAcc(dr_acc,dr);
				dr_eff = compareEff(dr_eff,dr);
			}
		}
		
		printResult("best err",dr_err);
		printResult("best acc",dr_acc);
		printResult("best eff",dr_eff);
		
		draw(total,original,file+"total.png",toDamage);
	}
	
	public static void main(String[] args) {
		final JFileChooser fc = new JFileChooser();
		fc.addChoosableFileFilter(new FileFilter() {
			@Override public String getDescription() {return "image file";}
			
			public String getExtension(final File f) {
				String ext = null;
		        final String s = f.getName();
		        final int i = s.lastIndexOf('.');
		        
		        if(i > 0 &&  i < s.length() - 1)
		        	ext = s.substring(i+1).toLowerCase();
		        return ext;
		    }
			
			@Override
			public boolean accept(File f) {
				if(f.isDirectory()) return true;
				
				final String extension = getExtension(f);
				if(extension != null && (
				   extension.equals("tiff") ||
				   extension.equals("tif") ||
				   extension.equals("gif") ||
				   extension.equals("jpeg") ||
				   extension.equals("jpg") ||
				   extension.equals("png")))
					return true;
				return false;
			}
		});
		if(fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) System.exit(0);
		String file = fc.getSelectedFile().getAbsolutePath();
		file = file.substring(0,file.lastIndexOf('.'));
		try {execute(file);} catch (IOException e) {e.printStackTrace();}
	}
}




























































