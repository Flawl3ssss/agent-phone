cmake_minimum_required(VERSION 3.22.1)
project(agentphone_native C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wall -Wextra")

# Исполняемый файл с именем libkexec.so: AGP упаковывает его в jniLibs/<abi>/,
# установщик выкладывает в nativeLibraryDir, и только оттуда разрешён execve.
add_executable(kexec kexec.c)
set_target_properties(kexec PROPERTIES PREFIX "lib" SUFFIX ".so")
