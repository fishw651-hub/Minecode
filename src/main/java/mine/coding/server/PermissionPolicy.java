package mine.coding.server;

import java.util.Set;

/**
 * 权限闸门：哪些请求会改变世界或扩大授权范围，必须先持有命令权限等级 2。
 * 纯表驱动，由 MineCodeService.handle 在分发前统一查表，避免新增入口时漏掉校验。
 */
public final class PermissionPolicy {
    /** approveProject 会把"半径 32"检查替换成项目边界检查，属于授权闸门，必须持权限。 */
    private static final Set<String> GUARDED=Set.of("confirm","undo","approveProject","clearMemory","logs");
    private PermissionPolicy(){}
    public static boolean requiresWorldPermission(String op){return GUARDED.contains(op);}
}
