#include <iostream>
#include <fstream>
#include <vector>
#include <string>
#include <cstdlib>
#include <stdexcept>
#include <algorithm>
#include <iomanip>
#include <sstream>

#include "mpi.h"
#include "omp.h"

#include <openssl/evp.h>
#include <openssl/aes.h>
#include <openssl/err.h>
#include <openssl/rand.h>

#pragma pack(push, 1)
struct BMPFileHeader
{
    uint16_t file_type{0x4D42};
    uint32_t file_size{0};
    uint16_t reserved1{0};
    uint16_t reserved2{0};
    uint32_t offset_data{0};
};

struct BMPInfoHeader
{
    uint32_t size{0};
    int32_t width{0};
    int32_t height{0};
    uint16_t planes{1};
    uint16_t bit_count{0};
    uint32_t compression{0};
    uint32_t size_image{0};
    int32_t x_pixels_per_meter{0};
    int32_t y_pixels_per_meter{0};
    uint32_t colors_used{0};
    uint32_t colors_important{0};
};
#pragma pack(pop)

std::vector<unsigned char> hex_to_bytes(const std::string &hex)
{
    std::vector<unsigned char> bytes;
    if (hex.length() % 2 != 0)
    {
        throw std::runtime_error("Hex string must have an even number of characters.");
    }
    for (size_t i = 0; i < hex.length(); i += 2)
    {
        std::string byteString = hex.substr(i, 2);
        try
        {
            unsigned char byte = static_cast<unsigned char>(std::stoul(byteString, nullptr, 16));
            bytes.push_back(byte);
        }
        catch (const std::invalid_argument &ia)
        {
            throw std::runtime_error("Invalid character in hex string: " + byteString);
        }
        catch (const std::out_of_range &oor)
        {
            throw std::runtime_error("Hex string value out of range: " + byteString);
        }
    }
    return bytes;
}

void handle_openssl_errors(const std::string &context_message = "")
{
    unsigned long err_code;
    std::cerr << "OpenSSL Error";
    if (!context_message.empty())
    {
        std::cerr << " (Context: " << context_message << ")";
    }
    std::cerr << ":" << std::endl;
    while ((err_code = ERR_get_error()))
    {
        char err_buf[256];
        ERR_error_string_n(err_code, err_buf, sizeof(err_buf));
        std::cerr << "  - " << err_buf << std::endl;
    }
}

std::vector<unsigned char> extract_pure_pixel_data(
    const std::vector<unsigned char> &raw_bmp_pixel_data,
    int width, int height,
    int bytes_per_pixel, int original_bmp_row_padding)
{
    std::vector<unsigned char> pure_data;

    if (width <= 0 || abs(height) <= 0 || bytes_per_pixel <= 0)
    {
        return pure_data;
    }

    int actual_row_width_bytes = width * bytes_per_pixel;
    if (actual_row_width_bytes <= 0)
    {
        return pure_data;
    }

    int bmp_padded_row_stride = actual_row_width_bytes + original_bmp_row_padding;
    if (bmp_padded_row_stride <= 0)
    {
        return pure_data;
    }

    if (!raw_bmp_pixel_data.empty())
    {
        size_t estimated_rows = (raw_bmp_pixel_data.size() + bmp_padded_row_stride - 1) / bmp_padded_row_stride;
        if (estimated_rows > 0 && actual_row_width_bytes > 0)
        {
            size_t estimated_pure_size = estimated_rows * actual_row_width_bytes;
            if (estimated_pure_size > 0)
                pure_data.reserve(estimated_pure_size);
        }
    }

    size_t current_raw_offset = 0;
    while (current_raw_offset < raw_bmp_pixel_data.size())
    {
        const unsigned char *current_segment_start_ptr = raw_bmp_pixel_data.data() + current_raw_offset;
        size_t remaining_bytes_in_raw_total = raw_bmp_pixel_data.size() - current_raw_offset;

        if (remaining_bytes_in_raw_total == 0)
            break;

        size_t current_segment_potential_length = std::min(static_cast<size_t>(bmp_padded_row_stride), remaining_bytes_in_raw_total);
        size_t data_part_of_segment;
        if (current_segment_potential_length > static_cast<size_t>(original_bmp_row_padding))
        {
            data_part_of_segment = current_segment_potential_length - static_cast<size_t>(original_bmp_row_padding);
        }
        else
        {
            data_part_of_segment = 0;
        }
        size_t data_bytes_to_copy_this_segment = std::min(data_part_of_segment, static_cast<size_t>(actual_row_width_bytes));

        if (data_bytes_to_copy_this_segment > 0)
        {
            pure_data.insert(pure_data.end(), current_segment_start_ptr, current_segment_start_ptr + data_bytes_to_copy_this_segment);
        }
        else
        {
            break;
        }
        current_raw_offset += static_cast<size_t>(bmp_padded_row_stride);
    }
    return pure_data;
}

std::vector<unsigned char> reconstruct_bmp_pixel_data(
    const std::vector<unsigned char> &processed_pure_pixel_data,
    int width, int height, int bytes_per_pixel, int original_bmp_row_padding)
{
    std::vector<unsigned char> reconstructed_data;
    int actual_row_width_bytes = width * bytes_per_pixel;
    int num_rows = abs(height);

    if (width <= 0 || num_rows <= 0)
    {
        return reconstructed_data;
    }

    size_t num_conceptual_rows_from_data = 0;
    if (actual_row_width_bytes > 0)
    {
        num_conceptual_rows_from_data = (processed_pure_pixel_data.size() + actual_row_width_bytes - 1) / actual_row_width_bytes;
    }
    else if (processed_pure_pixel_data.empty())
    {
        num_conceptual_rows_from_data = static_cast<size_t>(num_rows);
    }
    else
    {
        num_conceptual_rows_from_data = 1;
    }

    reconstructed_data.reserve(num_conceptual_rows_from_data * actual_row_width_bytes + num_conceptual_rows_from_data * original_bmp_row_padding);
    std::vector<unsigned char> padding_bytes(original_bmp_row_padding, 0);

    size_t data_processed_so_far = 0;
    for (size_t i = 0; i < num_conceptual_rows_from_data && data_processed_so_far < processed_pure_pixel_data.size(); ++i)
    {
        size_t current_pure_data_offset = data_processed_so_far;
        const unsigned char *pure_data_start_ptr = processed_pure_pixel_data.data() + current_pure_data_offset;

        size_t remaining_data_in_input = processed_pure_pixel_data.size() - current_pure_data_offset;
        size_t bytes_to_copy_for_this_row_segment = std::min(static_cast<size_t>(actual_row_width_bytes), remaining_data_in_input);

        if (bytes_to_copy_for_this_row_segment > 0)
        {
            reconstructed_data.insert(reconstructed_data.end(), pure_data_start_ptr, pure_data_start_ptr + bytes_to_copy_for_this_row_segment);
            data_processed_so_far += bytes_to_copy_for_this_row_segment;
        }

        reconstructed_data.insert(reconstructed_data.end(), padding_bytes.begin(), padding_bytes.end());
    }
    return reconstructed_data;
}

int main(int argc, char *argv[])
{
    MPI_Init(&argc, &argv);
    OpenSSL_add_all_algorithms();
    ERR_load_crypto_strings();

    int world_rank, world_size;
    MPI_Comm_rank(MPI_COMM_WORLD, &world_rank);
    MPI_Comm_size(MPI_COMM_WORLD, &world_size);

    std::string input_path, output_path, operation_str, key_size_str, mode_str, iv_hex_str;
    std::vector<unsigned char> key_bytes, iv_bytes;
    int expected_key_len_bits = 0;

    BMPFileHeader file_header;
    BMPInfoHeader info_header;
    int original_bmp_row_padding = 0;

    int true_original_pure_plaintext_size = 0;
    int size_for_crypto_operation = 0;

    try
    {
        if (argc < 6)
        {
            if (world_rank == 0)
                std::cerr << "Usage: " << argv[0] << " <input.bmp> <output.bmp> <encrypt|decrypt> <128|192|256> <ECB|CBC> [IV_hex_for_CBC]" << std::endl;
            MPI_Abort(MPI_COMM_WORLD, 1);
            return 1;
        }

        input_path = argv[1];
        output_path = argv[2];
        operation_str = argv[3];
        key_size_str = argv[4];
        mode_str = argv[5];
        if (argc > 6)
            iv_hex_str = argv[6];

        const char *key_env = std::getenv("PROCESSING_KEY");
        if (!key_env)
        {
            if (world_rank == 0)
                std::cerr << "Error: PROCESSING_KEY environment variable not set." << std::endl;
            MPI_Abort(MPI_COMM_WORLD, 1);
            return 1;
        }
        key_bytes = hex_to_bytes(std::string(key_env));
        expected_key_len_bits = std::stoi(key_size_str);

        if (expected_key_len_bits != 128 && expected_key_len_bits != 192 && expected_key_len_bits != 256)
        {
            if (world_rank == 0)
                std::cerr << "Error: Invalid key size. Must be 128, 192, or 256." << std::endl;
            MPI_Abort(MPI_COMM_WORLD, 1);
            return 1;
        }
        if (static_cast<int>(key_bytes.size() * 8) != expected_key_len_bits)
        {
            if (world_rank == 0)
                std::cerr << "Error: Key length (" << key_bytes.size() * 8 << " bits) does not match specified key size (" << expected_key_len_bits << " bits)." << std::endl;
            MPI_Abort(MPI_COMM_WORLD, 1);
            return 1;
        }

        std::transform(mode_str.begin(), mode_str.end(), mode_str.begin(), ::toupper);
        if (mode_str == "CBC")
        {
            if (iv_hex_str.empty())
            {
                if (world_rank == 0)
                    std::cerr << "Error: IV must be provided for CBC mode." << std::endl;
                MPI_Abort(MPI_COMM_WORLD, 1);
                return 1;
            }
            iv_bytes = hex_to_bytes(iv_hex_str);
            if (iv_bytes.size() != AES_BLOCK_SIZE)
            {
                if (world_rank == 0)
                    std::cerr << "Error: IV length must be " << AES_BLOCK_SIZE << " bytes for CBC mode." << std::endl;
                MPI_Abort(MPI_COMM_WORLD, 1);
                return 1;
            }
        }
        else if (mode_str != "ECB")
        {
            if (world_rank == 0)
                std::cerr << "Error: Invalid mode. Must be ECB or CBC." << std::endl;
            MPI_Abort(MPI_COMM_WORLD, 1);
            return 1;
        }

        if (operation_str != "encrypt" && operation_str != "decrypt")
        {
            if (world_rank == 0)
                std::cerr << "Error: Invalid operation. Must be encrypt or decrypt." << std::endl;
            MPI_Abort(MPI_COMM_WORLD, 1);
            return 1;
        }

        std::vector<unsigned char> pure_pixel_data_buffer;

        if (world_rank == 0)
        {
            std::cout << "Rank 0: Processing " << input_path << " -> " << output_path << std::endl;
            std::cout << "Operation: " << operation_str << ", Mode: " << mode_str << ", Key Size: " << expected_key_len_bits << std::endl;

            std::ifstream file(input_path, std::ios::binary);
            if (!file)
            {
                std::cerr << "Error opening input file: " << input_path << std::endl;
                MPI_Abort(MPI_COMM_WORLD, 1);
                return 1;
            }

            file.read(reinterpret_cast<char *>(&file_header), sizeof(file_header));
            file.read(reinterpret_cast<char *>(&info_header), sizeof(info_header));

            if (file_header.file_type != 0x4D42)
            {
                std::cerr << "Not a BMP file." << std::endl;
                MPI_Abort(MPI_COMM_WORLD, 1);
                return 1;
            }
            if (info_header.bit_count != 24 && info_header.bit_count != 32)
            {
                std::cerr << "Only 24/32 bpp supported." << std::endl;
                MPI_Abort(MPI_COMM_WORLD, 1);
                return 1;
            }
            if (info_header.compression != 0)
            {
                std::cerr << "Compressed BMP not supported." << std::endl;
                MPI_Abort(MPI_COMM_WORLD, 1);
                return 1;
            }
            if (info_header.width <= 0 || info_header.height == 0)
            {
                std::cerr << "Invalid BMP dimensions." << std::endl;
                MPI_Abort(MPI_COMM_WORLD, 1);
                return 1;
            }

            int bytes_per_pixel = info_header.bit_count / 8;
            int actual_row_width_bytes_calc = info_header.width * bytes_per_pixel;
            int bmp_padded_row_stride_calculated_in_main = (actual_row_width_bytes_calc + 3) & ~3;
            original_bmp_row_padding = bmp_padded_row_stride_calculated_in_main - actual_row_width_bytes_calc;

            if (info_header.width > 0 && abs(info_header.height) > 0 && bytes_per_pixel > 0)
            {
                true_original_pure_plaintext_size = abs(info_header.height) * info_header.width * bytes_per_pixel;
            }
            std::cout << "Rank 0: True original pure plaintext size (calculated from headers): " << true_original_pure_plaintext_size << std::endl;

            std::vector<unsigned char> raw_bmp_pixel_data;
            size_t expected_pixel_data_size;

            file.seekg(file_header.offset_data, std::ios::beg);

            if (info_header.size_image != 0)
            {
                expected_pixel_data_size = info_header.size_image;
            }
            else
            {
                expected_pixel_data_size = static_cast<size_t>(abs(info_header.height)) * bmp_padded_row_stride_calculated_in_main;
            }

            std::cout << "Rank 0: Expected pixel data size from header/calculation: " << expected_pixel_data_size << std::endl;

            if (expected_pixel_data_size > 0)
            {
                raw_bmp_pixel_data.resize(expected_pixel_data_size);
                file.read(reinterpret_cast<char *>(raw_bmp_pixel_data.data()), expected_pixel_data_size);
                std::streamsize gcount = file.gcount();

                if (file.fail() && !file.eof())
                {
                    std::cerr << "Rank 0 Error: File stream error after attempting to read pixel data. Status: eof=" << file.eof() << " fail=" << file.fail() << " bad=" << file.bad() << std::endl;
                    file.close();
                    MPI_Abort(MPI_COMM_WORLD, 1);
                    return 1;
                }

                if (gcount < static_cast<std::streamsize>(expected_pixel_data_size))
                {
                    std::cout << "Rank 0 Warning: Read " << gcount << " bytes of pixel data, but expected " << expected_pixel_data_size
                              << ". Using actual bytes read (" << (gcount < 0 ? 0 : gcount) << "). This might indicate a truncated or malformed BMP." << std::endl;
                    raw_bmp_pixel_data.resize(gcount < 0 ? 0 : static_cast<size_t>(gcount));
                }
                std::cout << "Rank 0: Actual size of raw_bmp_pixel_data after read and potential resize: " << raw_bmp_pixel_data.size() << std::endl;
            }
            else
            {
                std::cout << "Rank 0: Expected pixel data size is 0. raw_bmp_pixel_data will be empty." << std::endl;
            }
            file.close();

            if (info_header.size_image == 0 && raw_bmp_pixel_data.empty() &&
                (static_cast<size_t>(abs(info_header.height)) * bmp_padded_row_stride_calculated_in_main > 0))
            {
                std::cout << "Rank 0 Warning: BMP header's size_image was 0, calculated size was > 0, but read 0 bytes for pixel data. The file might be truncated before pixel data section or is an empty image." << std::endl;
            }

            std::vector<unsigned char> temp_extracted_data = extract_pure_pixel_data(raw_bmp_pixel_data, info_header.width, info_header.height, bytes_per_pixel, original_bmp_row_padding);
            std::cout << "Rank 0: Size of data extracted by extract_pure_pixel_data: " << temp_extracted_data.size() << std::endl;

            if (operation_str == "encrypt")
            {
                std::cout << "Rank 0: Operation: ENCRYPT" << std::endl;
                pure_pixel_data_buffer = temp_extracted_data;

                if (mode_str == "ECB")
                {
                    size_for_crypto_operation = ((temp_extracted_data.size() + AES_BLOCK_SIZE - 1) / AES_BLOCK_SIZE) * AES_BLOCK_SIZE;
                    if (temp_extracted_data.size() > 0 && size_for_crypto_operation == 0)
                        size_for_crypto_operation = AES_BLOCK_SIZE;
                    pure_pixel_data_buffer.resize(size_for_crypto_operation, 0);
                    std::cout << "Rank 0: ECB mode. Plaintext manually AES-padded to: " << size_for_crypto_operation << std::endl;
                }
                else
                {
                    size_for_crypto_operation = temp_extracted_data.size();
                    std::cout << "Rank 0: CBC mode. Plaintext size for OpenSSL (will pad): " << size_for_crypto_operation << std::endl;
                }
            }
            else
            {
                std::cout << "Rank 0: Operation: DECRYPT" << std::endl;
                pure_pixel_data_buffer = temp_extracted_data;
                size_for_crypto_operation = temp_extracted_data.size();
                std::cout << "Rank 0: Ciphertext size for OpenSSL: " << size_for_crypto_operation << std::endl;

                if (size_for_crypto_operation == 0 && true_original_pure_plaintext_size > 0)
                {
                    std::cerr << "Rank 0 Error: Decryption - Extracted ciphertext is empty, but original image was expected to have content ("
                              << true_original_pure_plaintext_size << " bytes). Aborting." << std::endl;
                    MPI_Abort(MPI_COMM_WORLD, 1);
                    return 1;
                }
                if (size_for_crypto_operation == 0 && true_original_pure_plaintext_size == 0)
                {
                    std::cout << "Rank 0: Decryption - Extracted ciphertext is empty, and original image was also expected to be empty. Proceeding." << std::endl;
                }

                if (mode_str == "CBC")
                {
                    if (size_for_crypto_operation > 0 && size_for_crypto_operation % AES_BLOCK_SIZE != 0)
                    {
                        std::cerr << "Rank 0 Error: CBC Decryption - Ciphertext size (" << size_for_crypto_operation
                                  << ") is not a multiple of AES_BLOCK_SIZE (" << AES_BLOCK_SIZE
                                  << "). This indicates a corrupted or truncated input file. Aborting." << std::endl;
                        MPI_Abort(MPI_COMM_WORLD, 1);
                        return 1;
                    }
                }
                else
                {
                    if (size_for_crypto_operation > 0 && size_for_crypto_operation % AES_BLOCK_SIZE != 0)
                    {
                        std::cerr << "Rank 0 Warning: ECB Decryption - Ciphertext size (" << size_for_crypto_operation
                                  << ") is not a multiple of AES_BLOCK_SIZE. Decryption might produce incorrect results due to unpadding issues." << std::endl;
                    }
                }
            }
        }

        MPI_Bcast(&file_header, sizeof(BMPFileHeader), MPI_BYTE, 0, MPI_COMM_WORLD);
        MPI_Bcast(&info_header, sizeof(BMPInfoHeader), MPI_BYTE, 0, MPI_COMM_WORLD);
        MPI_Bcast(&original_bmp_row_padding, 1, MPI_INT, 0, MPI_COMM_WORLD);
        MPI_Bcast(&true_original_pure_plaintext_size, 1, MPI_INT, 0, MPI_COMM_WORLD);
        MPI_Bcast(&size_for_crypto_operation, 1, MPI_INT, 0, MPI_COMM_WORLD);

        if (world_rank != 0)
        {
            pure_pixel_data_buffer.resize(size_for_crypto_operation);
        }
        if (size_for_crypto_operation > 0)
        {
            MPI_Bcast(pure_pixel_data_buffer.data(), size_for_crypto_operation, MPI_UNSIGNED_CHAR, 0, MPI_COMM_WORLD);
        }

        std::vector<unsigned char> processed_local_data;
        int local_offset = 0;
        int local_chunk_size_val = 0;

        if (mode_str == "ECB" && size_for_crypto_operation > 0)
        {
            int num_total_blocks = size_for_crypto_operation / AES_BLOCK_SIZE;
            int blocks_per_rank = num_total_blocks / world_size;
            int extra_blocks = num_total_blocks % world_size;

            local_chunk_size_val = (blocks_per_rank + (world_rank < extra_blocks ? 1 : 0)) * AES_BLOCK_SIZE;

            for (int i = 0; i < world_rank; ++i)
            {
                local_offset += (blocks_per_rank + (i < extra_blocks ? 1 : 0)) * AES_BLOCK_SIZE;
            }

            if (local_chunk_size_val > 0)
            {
                if (static_cast<size_t>(local_offset + local_chunk_size_val) > pure_pixel_data_buffer.size())
                {
                    std::cerr << "Rank " << world_rank << ": ECB - Calculated chunk [offset " << local_offset << ", size " << local_chunk_size_val
                              << "] exceeds pure_pixel_data_buffer size (" << pure_pixel_data_buffer.size() << "). Aborting." << std::endl;
                    MPI_Abort(MPI_COMM_WORLD, 1);
                    return 1;
                }
                processed_local_data.resize(local_chunk_size_val);
            }
        }
        else if (mode_str == "CBC" && world_rank == 0 && size_for_crypto_operation > 0)
        {
            local_chunk_size_val = size_for_crypto_operation;
            processed_local_data.resize(size_for_crypto_operation + AES_BLOCK_SIZE);
        }

        if (local_chunk_size_val > 0)
        {
            EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
            if (!ctx)
            {
                handle_openssl_errors("EVP_CIPHER_CTX_new");
                MPI_Abort(MPI_COMM_WORLD, 1);
                return 1;
            }

            const EVP_CIPHER *cipher = nullptr;
            if (mode_str == "ECB")
            {
                if (expected_key_len_bits == 128)
                    cipher = EVP_aes_128_ecb();
                else if (expected_key_len_bits == 192)
                    cipher = EVP_aes_192_ecb();
                else
                    cipher = EVP_aes_256_ecb();
            }
            else
            {
                if (expected_key_len_bits == 128)
                    cipher = EVP_aes_128_cbc();
                else if (expected_key_len_bits == 192)
                    cipher = EVP_aes_192_cbc();
                else
                    cipher = EVP_aes_256_cbc();
            }

            int op_encrypt = (operation_str == "encrypt") ? 1 : 0;

            if (1 != EVP_CipherInit_ex(ctx, cipher, nullptr, key_bytes.data(), (mode_str == "CBC" ? iv_bytes.data() : nullptr), op_encrypt))
            {
                handle_openssl_errors("EVP_CipherInit_ex");
                EVP_CIPHER_CTX_free(ctx);
                MPI_Abort(MPI_COMM_WORLD, 1);
                return 1;
            }

            if (mode_str == "ECB")
            {
                if (1 != EVP_CIPHER_CTX_set_padding(ctx, 0))
                {
                    handle_openssl_errors("EVP_CIPHER_CTX_set_padding(0) for ECB");
                    EVP_CIPHER_CTX_free(ctx);
                    MPI_Abort(MPI_COMM_WORLD, 1);
                    return 1;
                }
            }
            else
            {
                if (1 != EVP_CIPHER_CTX_set_padding(ctx, 1))
                {
                    handle_openssl_errors("EVP_CIPHER_CTX_set_padding(1) for CBC");
                    EVP_CIPHER_CTX_free(ctx);
                    MPI_Abort(MPI_COMM_WORLD, 1);
                    return 1;
                }
            }

            int out_len1 = 0;
            const unsigned char *input_chunk_ptr = (mode_str == "ECB") ? (pure_pixel_data_buffer.data() + local_offset) : pure_pixel_data_buffer.data();

            if (1 != EVP_CipherUpdate(ctx, processed_local_data.data(), &out_len1, input_chunk_ptr, local_chunk_size_val))
            {
                handle_openssl_errors("EVP_CipherUpdate");
                EVP_CIPHER_CTX_free(ctx);
                MPI_Abort(MPI_COMM_WORLD, 1);
                return 1;
            }

            int out_len2 = 0;
            if (1 != EVP_CipherFinal_ex(ctx, processed_local_data.data() + out_len1, &out_len2))
            {
                handle_openssl_errors("EVP_CipherFinal_ex");
                EVP_CIPHER_CTX_free(ctx);
                MPI_Abort(MPI_COMM_WORLD, 1);
                return 1;
            }
            processed_local_data.resize(out_len1 + out_len2);
            EVP_CIPHER_CTX_free(ctx);
        }

        std::vector<unsigned char> final_processed_crypto_data;
        if (world_rank == 0)
        {
            if (size_for_crypto_operation > 0)
            {
                if (mode_str == "ECB")
                {
                    final_processed_crypto_data.resize(size_for_crypto_operation);
                }
                else
                {
                }
            }
        }

        if (mode_str == "ECB" && size_for_crypto_operation > 0)
        {
            std::vector<int> recvcounts(world_size);
            std::vector<int> displs(world_size);
            int current_offset_gather = 0;
            int num_total_blocks = size_for_crypto_operation / AES_BLOCK_SIZE;
            int blocks_per_rank = num_total_blocks / world_size;
            int extra_blocks = num_total_blocks % world_size;

            for (int i = 0; i < world_size; ++i)
            {
                recvcounts[i] = (blocks_per_rank + (i < extra_blocks ? 1 : 0)) * AES_BLOCK_SIZE;
                displs[i] = current_offset_gather;
                current_offset_gather += recvcounts[i];
            }

            MPI_Gatherv(processed_local_data.data(), processed_local_data.size(), MPI_UNSIGNED_CHAR,
                        final_processed_crypto_data.data(), recvcounts.data(), displs.data(), MPI_UNSIGNED_CHAR,
                        0, MPI_COMM_WORLD);
        }
        else if (mode_str == "CBC" && world_rank == 0 && size_for_crypto_operation > 0)
        {
            final_processed_crypto_data = processed_local_data;
        }

        if (world_rank == 0)
        {
            bool has_data_to_finalize = !final_processed_crypto_data.empty();
            if (mode_str == "ECB" && size_for_crypto_operation > 0 && final_processed_crypto_data.empty() && world_size > 0)
            {
                if (final_processed_crypto_data.size() != static_cast<size_t>(size_for_crypto_operation) && size_for_crypto_operation > 0)
                {
                    std::cerr << "Rank 0 Warning: ECB - final_processed_crypto_data size (" << final_processed_crypto_data.size()
                              << ") does not match expected crypto size (" << size_for_crypto_operation << ")." << std::endl;
                }
            }

            if (size_for_crypto_operation > 0 && !final_processed_crypto_data.empty())
            {
                std::vector<unsigned char> final_pure_data_unpadded_for_bmp;

                if (mode_str == "ECB")
                {
                    if (final_processed_crypto_data.size() < static_cast<size_t>(true_original_pure_plaintext_size))
                    {
                        std::cerr << "Rank 0 Error: ECB processed data size (" << final_processed_crypto_data.size()
                                  << ") is less than true original pure plaintext size (" << true_original_pure_plaintext_size
                                  << "). Cannot unpad correctly. Aborting." << std::endl;
                        MPI_Abort(MPI_COMM_WORLD, 1);
                        return 1;
                    }
                    size_t unpad_count = std::min(final_processed_crypto_data.size(), static_cast<size_t>(true_original_pure_plaintext_size));
                    final_pure_data_unpadded_for_bmp.assign(final_processed_crypto_data.begin(), final_processed_crypto_data.begin() + unpad_count);

                    std::cout << "Rank 0: ECB data unpadded from " << final_processed_crypto_data.size()
                              << " to target original pure size: " << final_pure_data_unpadded_for_bmp.size() << std::endl;
                    if (unpad_count != static_cast<size_t>(true_original_pure_plaintext_size))
                    {
                        std::cout << "Rank 0: Warning - ECB unpadded size " << unpad_count
                                  << " does not match true_original_pure_plaintext_size " << true_original_pure_plaintext_size << std::endl;
                    }
                }
                else
                {
                    final_pure_data_unpadded_for_bmp = final_processed_crypto_data;
                    std::cout << "Rank 0: CBC data (OpenSSL unpadded) size: " << final_pure_data_unpadded_for_bmp.size() << std::endl;

                    if (operation_str == "decrypt" && final_pure_data_unpadded_for_bmp.size() != static_cast<size_t>(true_original_pure_plaintext_size))
                    {
                        std::cout << "Rank 0: Warning - CBC decrypted size (" << final_pure_data_unpadded_for_bmp.size()
                                  << ") differs from true original pure plaintext size (" << true_original_pure_plaintext_size
                                  << "). This may be normal if original data wasn't block-aligned for encryption, or indicates an issue." << std::endl;
                    }
                }

                if (final_pure_data_unpadded_for_bmp.empty() && true_original_pure_plaintext_size > 0)
                {
                    std::cerr << "Rank 0 Error: final_pure_data_unpadded_for_bmp is empty but original was not. Aborting before BMP reconstruction." << std::endl;
                    MPI_Abort(MPI_COMM_WORLD, 1);
                    return 1;
                }

                int bytes_per_pixel = info_header.bit_count / 8;
                std::vector<unsigned char> output_bmp_pixel_data = reconstruct_bmp_pixel_data(
                    final_pure_data_unpadded_for_bmp, info_header.width, info_header.height, bytes_per_pixel, original_bmp_row_padding);

                file_header.file_size = file_header.offset_data + output_bmp_pixel_data.size();
                info_header.size_image = output_bmp_pixel_data.size();

                std::ofstream outfile(output_path, std::ios::binary);
                if (!outfile)
                {
                    std::cerr << "Error opening output file: " << output_path << std::endl;
                    MPI_Abort(MPI_COMM_WORLD, 1);
                    return 1;
                }
                outfile.write(reinterpret_cast<const char *>(&file_header), sizeof(file_header));
                outfile.write(reinterpret_cast<const char *>(&info_header), sizeof(info_header));
                outfile.write(reinterpret_cast<const char *>(output_bmp_pixel_data.data()), output_bmp_pixel_data.size());
                outfile.close();
                std::cout << "Rank 0: Successfully wrote processed image to " << output_path << std::endl;
            }
            else
            {
                std::cout << "Rank 0: No processed data to write (original data might have been empty or processing failed). Output file will be empty or not modified significantly." << std::endl;
                std::ofstream outfile(output_path, std::ios::binary);
                outfile.close();
            }
        }
    }
    catch (const std::exception &e)
    {
        if (world_rank == 0)
        {
            std::cerr << "Rank 0 Exception: " << e.what() << std::endl;
        }
        else
        {
            std::cerr << "Rank " << world_rank << " Exception: " << e.what() << std::endl;
        }
        if (std::string(e.what()).find("OpenSSL") != std::string::npos || ERR_peek_error() != 0)
        {
            if (world_rank == 0)
                handle_openssl_errors("Exception caught in main");
        }
        MPI_Abort(MPI_COMM_WORLD, 1);
    }
    catch (...)
    {
        if (world_rank == 0)
        {
            std::cerr << "Rank 0: Unknown exception caught!" << std::endl;
        }
        else
        {
            std::cerr << "Rank " << world_rank << ": Unknown exception caught!" << std::endl;
        }
        MPI_Abort(MPI_COMM_WORLD, 1);
    }

    MPI_Barrier(MPI_COMM_WORLD);
    if (world_rank == 0)
    {
        std::cout << "=== PROCESSING FINISHED ===" << std::endl;
    }

    ERR_free_strings();
    EVP_cleanup();
    MPI_Finalize();
    return 0;
}
