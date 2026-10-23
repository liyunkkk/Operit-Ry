#pragma once

#include <jni.h>
#include <vector>

#include "StreamGroup.h"

namespace streamnative {

std::vector<Segment> splitByXml(const jchar* chars, int len);

class MarkdownSession;

MarkdownSession* createMarkdownBlockSession();
MarkdownSession* createMarkdownInlineSession();
void destroyMarkdownSession(MarkdownSession* session);

std::vector<Segment> markdownSessionPush(MarkdownSession* session, const jchar* chars, int len);
// End of input: characters buffered while a plugin was still TRYING are emitted as plain text.
std::vector<Segment> markdownSessionFinish(MarkdownSession* session);

} // namespace streamnative
