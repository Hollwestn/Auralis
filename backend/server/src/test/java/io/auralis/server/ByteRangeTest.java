package io.auralis.server;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ByteRangeTest {
    @Test void wholeFile(){assertEquals(new ByteRange(0,99,false),ByteRange.parse(null,100));}
    @Test void closedRange(){assertEquals(new ByteRange(20,39,true),ByteRange.parse("bytes=20-39",100));}
    @Test void openRange(){assertEquals(new ByteRange(90,99,true),ByteRange.parse("bytes=90-",100));}
    @Test void suffix(){assertEquals(new ByteRange(90,99,true),ByteRange.parse("bytes=-10",100));}
    @Test void clampsEnd(){assertEquals(new ByteRange(0,99,true),ByteRange.parse("bytes=0-999",100));}
    @Test void rejectsUnsatisfiableAndAmplifiedRequests(){for(String h:new String[]{"bytes=100-","bytes=-0","bytes=10-1","bytes=0-1,4-5","items=1-2","bytes=-","bytes=999999999999999999999-"})assertThrows(IllegalArgumentException.class,()->ByteRange.parse(h,100),h);}
}
