package enums;

public enum DataTypeEnums {
    INT("int", 32, Integer.class),
    LONG("long", 64, Long.class),
    FLOAT("float", 32, Float.class),
    DOUBLE("double", 64, Double.class);


    private final String type;
    private final int size;

    private final Class<?> clazz;

    DataTypeEnums(String type, int size, Class<?> clazz) {
        this.type = type;
        this.size = size;
        this.clazz = clazz;
    }

    public String getType() {
        return type;
    }

    public Class<?> getClazz(){return clazz;}

    public int getSize() {
        return size;
    }
}
