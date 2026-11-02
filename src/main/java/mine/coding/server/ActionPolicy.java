package mine.coding.server;

import java.util.Locale;
import java.util.regex.Pattern;

/** Pure server-side safety checks shared by preview and confirmation. */
public final class ActionPolicy {
    public static final int MAX_RADIUS = 32;
    public static final long MAX_ACTION_BLOCKS = 4096;
    public static final long MAX_PLAN_BLOCKS = 8192;
    public static final int MAX_ACTIONS = 16;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern COMMAND = Pattern.compile("[a-z0-9_:.+-]+(?:\\s+.*)?", Pattern.CASE_INSENSITIVE);
    private ActionPolicy() {}
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}
    public static Box box(int x, int y, int z, int x2, int y2, int z2) {
        int minX = Math.min(x,x2), maxX = Math.max(x,x2), minY=Math.min(y,y2), maxY=Math.max(y,y2), minZ=Math.min(z,z2), maxZ=Math.max(z,z2);
        long dx=(long)maxX-minX+1, dy=(long)maxY-minY+1, dz=(long)maxZ-minZ+1;
        long volume;
        try { volume=Math.multiplyExact(Math.multiplyExact(dx,dy),dz); } catch (ArithmeticException e) { throw new IllegalArgumentException("block volume overflow",e); }
        if (volume > MAX_ACTION_BLOCKS) throw new IllegalArgumentException("action exceeds block limit");
        return new Box(minX,minY,minZ,maxX,maxY,maxZ);
    }
    public static long volume(Box b) { return ((long)b.maxX()-b.minX()+1)*((long)b.maxY()-b.minY()+1)*((long)b.maxZ()-b.minZ()+1); }
    public static long totalVolume(Iterable<Box> boxes) { long total=0; for (Box b: boxes) { try { total=Math.addExact(total,volume(b)); } catch (ArithmeticException e) { throw new IllegalArgumentException("plan block volume overflow",e); } if(total>MAX_PLAN_BLOCKS) throw new IllegalArgumentException("plan exceeds block limit"); } return total; }
    public static void checkRange(Box b,double px,double py,double pz) { double maxSquared=0; for (int x : new int[]{b.minX(), b.maxX()}) for (int y : new int[]{b.minY(), b.maxY()}) for (int z : new int[]{b.minZ(), b.maxZ()}) { double dx=x-px, dy=y-py, dz=z-pz; maxSquared=Math.max(maxSquared, dx*dx+dy*dy+dz*dz); } if(maxSquared>MAX_RADIUS*MAX_RADIUS) throw new IllegalArgumentException("target outside player radius"); }
    /** dispatcher.execute 返回 0 表示命令没有执行/没有效果，必须按失败处理。 */
    public static boolean commandSucceeded(int dispatcherResult) { return dispatcherResult>0; }
    public static String safeCommand(String input) {        if (input == null) throw new IllegalArgumentException("command missing");
        String command=input.trim(); if(command.startsWith("/")) command=command.substring(1).trim();
        if(command.isEmpty() || command.length()>256 || command.indexOf('\n')>=0 || command.indexOf('\r')>=0 || command.indexOf(';')>=0 || command.indexOf('\0')>=0 || !COMMAND.matcher(command).matches()) throw new IllegalArgumentException("invalid command");
        String first=WHITESPACE.split(command,2)[0].toLowerCase(Locale.ROOT);
        boolean allowed=(first.equals("say") && command.length()>4) || first.equals("seed") || first.equals("list") || command.matches("time query (day|daytime|gametime)");
        if(!allowed) throw new IllegalArgumentException("command is not allowed");
        return command;
    }
}




