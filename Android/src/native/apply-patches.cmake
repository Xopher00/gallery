# Applies comma-separated PATCHES in order inside the working directory. Already-applied patches are
# skipped; a patch that applies in neither direction fails loudly rather than being silently skipped.
# GIT_DIR points nowhere so git acts as a plain patch tool: inside the fork's repository `git apply` would
# otherwise skip every file outside the current subdirectory and still exit 0.
string(REPLACE "," ";" _patches "${PATCHES}")
set(_git ${CMAKE_COMMAND} -E env GIT_DIR=${CMAKE_CURRENT_LIST_DIR}/.no-git-dir git)
foreach(_p IN LISTS _patches)
  execute_process(COMMAND ${_git} apply --reverse --check "${_p}" RESULT_VARIABLE _applied OUTPUT_QUIET ERROR_QUIET)
  if(_applied EQUAL 0)
    message(STATUS "patch already applied: ${_p}")
    continue()
  endif()
  execute_process(COMMAND ${_git} apply -v "${_p}" RESULT_VARIABLE _rc OUTPUT_VARIABLE _out ERROR_VARIABLE _err)
  if(NOT _rc EQUAL 0 OR "${_out}${_err}" MATCHES "Skipped patch")
    message(FATAL_ERROR "patch did not apply: ${_p}\n${_out}${_err}")
  endif()
  message(STATUS "patch applied: ${_p}")
endforeach()
