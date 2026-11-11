import java.lang.reflect.Field;

/** Print the shipped XiamenLine table straight out of the built jar (no MC needed: the enum is pure strings). */
public class LineTable {
    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("dev.js.mtrheadlight.xiamen.XiamenLine");
        Field suffix = c.getField("suffix");
        Field number = c.getField("number");
        java.lang.reflect.Method doorLight = c.getMethod("doorLight");
        java.lang.reflect.Method variant = c.getMethod("textureVariant");
        Object[] consts = c.getEnumConstants();
        System.out.println("constants: " + consts.length);
        for (Object o : consts) {
            System.out.printf("%-4s suffix=%-4s number=%-2s doorLight=%-6s textureVariant=%-5s id(psd_door_xiamen)=%s%n",
                    ((Enum<?>) o).name(), "'" + suffix.get(o) + "'", number.get(o),
                    doorLight.invoke(o), variant.invoke(o),
                    c.getMethod("id", String.class).invoke(o, "psd_door_xiamen"));
        }
    }
}
