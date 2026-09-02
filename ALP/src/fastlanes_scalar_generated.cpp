#include "alp/config.hpp"
#include "fastlanes/ffor.hpp"
#include "fastlanes/unffor.hpp"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace {

template <typename U>
void pack_for(const U* input, U* output, uint8_t bit_width, const U* base_ptr) {
	constexpr size_t WORD_BITS = sizeof(U) * 8;
	std::fill_n(output, alp::config::VECTOR_SIZE, U {0});

	if (bit_width == 0) { return; }

	const U mask = bit_width == WORD_BITS
	                   ? std::numeric_limits<U>::max()
	                   : static_cast<U>((U {1} << bit_width) - U {1});
	const U base = *base_ptr;

	for (size_t i = 0; i < alp::config::VECTOR_SIZE; ++i) {
		const U delta = static_cast<U>(input[i] - base) & mask;
		const size_t bit_position = i * bit_width;
		const size_t word_index   = bit_position / WORD_BITS;
		const size_t bit_offset   = bit_position % WORD_BITS;

		output[word_index] |= static_cast<U>(delta << bit_offset);
		if (bit_offset + bit_width > WORD_BITS) {
			output[word_index + 1] |= static_cast<U>(delta >> (WORD_BITS - bit_offset));
		}
	}
}

template <typename U>
void unpack_for(const U* input, U* output, uint8_t bit_width, const U* base_ptr) {
	constexpr size_t WORD_BITS = sizeof(U) * 8;
	const U base = *base_ptr;

	if (bit_width == 0) {
		std::fill_n(output, alp::config::VECTOR_SIZE, base);
		return;
	}

	const U mask = bit_width == WORD_BITS
	                   ? std::numeric_limits<U>::max()
	                   : static_cast<U>((U {1} << bit_width) - U {1});

	for (size_t i = 0; i < alp::config::VECTOR_SIZE; ++i) {
		const size_t bit_position = i * bit_width;
		const size_t word_index   = bit_position / WORD_BITS;
		const size_t bit_offset   = bit_position % WORD_BITS;

		U delta = static_cast<U>(input[word_index] >> bit_offset);
		if (bit_offset + bit_width > WORD_BITS) {
			delta |= static_cast<U>(input[word_index + 1] << (WORD_BITS - bit_offset));
		}
		delta &= mask;
		output[i] = static_cast<U>(delta + base);
	}
}

} // namespace

namespace fastlanes::generated::ffor::fallback::scalar {

void ffor(const uint64_t* input, uint64_t* output, uint8_t bit_width, const uint64_t* base) {
	pack_for(input, output, bit_width, base);
}

void ffor(const uint32_t* input, uint32_t* output, uint8_t bit_width, const uint32_t* base) {
	pack_for(input, output, bit_width, base);
}

void ffor(const uint16_t* input, uint16_t* output, uint8_t bit_width, const uint16_t* base) {
	pack_for(input, output, bit_width, base);
}

void ffor(const uint8_t* input, uint8_t* output, uint8_t bit_width, const uint8_t* base) {
	pack_for(input, output, bit_width, base);
}

} // namespace fastlanes::generated::ffor::fallback::scalar

namespace fastlanes::generated::unffor::fallback::scalar {

void unffor(const uint64_t* input, uint64_t* output, uint8_t bit_width, const uint64_t* base) {
	unpack_for(input, output, bit_width, base);
}

void unffor(const uint32_t* input, uint32_t* output, uint8_t bit_width, const uint32_t* base) {
	unpack_for(input, output, bit_width, base);
}

void unffor(const uint16_t* input, uint16_t* output, uint8_t bit_width, const uint16_t* base) {
	unpack_for(input, output, bit_width, base);
}

void unffor(const uint8_t* input, uint8_t* output, uint8_t bit_width, const uint8_t* base) {
	unpack_for(input, output, bit_width, base);
}

} // namespace fastlanes::generated::unffor::fallback::scalar
