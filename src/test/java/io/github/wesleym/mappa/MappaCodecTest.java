package io.github.wesleym.mappa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MappaCodecTest {

	@Test
	void roundTripsNativeBytes() throws Exception {
		MappaMap map = MappaBuilderTest.sample();
		byte[] bytes = map.toBytes();

		assertArrayEquals(new byte[] { 'M', 'A', 'P', 'P', 'A' }, java.util.Arrays.copyOf(bytes, 5));
		MappaMap read = Mappa.read(bytes);
		assertEquals(map.title(), read.title());
		assertEquals(map.entities(), read.entities());
		assertEquals(map.relationships(), read.relationships());
	}

	@Test
	void writesAndReadsFiles(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("store.mappa");
		MappaMap map = MappaBuilderTest.sample();

		map.write(file);
		MappaMap read = Mappa.read(file);

		assertEquals(map.entities(), read.entities());
		assertEquals(map.relationships(), read.relationships());
	}

	@Test
	void nativeBytesAreNotPlainJson() {
		String text = new String(MappaBuilderTest.sample().toBytes(), StandardCharsets.ISO_8859_1);

		assertFalse(text.startsWith("{"));
		assertFalse(text.contains("\"entities\""));
	}
}
